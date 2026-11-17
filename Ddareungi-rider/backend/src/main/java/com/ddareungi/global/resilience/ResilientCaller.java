package com.ddareungi.global.resilience;

import com.ddareungi.global.metrics.ProviderCallMetrics;
import com.ddareungi.global.metrics.ProviderCallOutcome;
import org.jspecify.annotations.Nullable;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Runs one HTTP exchange with a provider under the request {@link Deadline}, an optional {@link CircuitBreaker}, a
 * bulkhead ({@link Semaphore}) and, for idempotent GETs, at most {@code maxRetries} retries via Spring's
 * {@link RetryTemplate}.
 *
 * <ul>
 *   <li>Retried (GET only, while the deadline leaves room): timeouts, 5xx, connection errors.</li>
 *   <li>Never retried and never counted by the breaker: 4xx including 429.</li>
 *   <li>Each attempt runs on a virtual thread capped at the remaining deadline and is cancelled when it runs out.</li>
 * </ul>
 * Review M7: a timeout that only happened because the remaining request deadline was shorter than the provider's
 * read timeout (the budget was mostly spent elsewhere) is reported to the breaker as ignored, not as a failure,
 * unless an earlier attempt of the same call already failed on its own. Outcomes are reported with the breaker
 * permit (generation) of this call, so a call admitted before the breaker opened is never taken for a probe.
 * The attempt should only perform the exchange; parsing the body (e.g. "no road route") stays outside so that
 * successful answers with a domain-level "not found" are never failures.
 * Every call, including rejected ones, is timed into {@link ProviderCallMetrics} with its {@link ProviderCallOutcome}
 * (phase 3 O8; a no-op without a meter registry).
 */
public final class ResilientCaller {
    /** A retry is only worth starting with at least this much budget left after the delay. */
    private static final Duration MIN_ATTEMPT_BUDGET = Duration.ofMillis(50);

    private final String name;
    private final @Nullable CircuitBreaker breaker;
    private final Semaphore bulkhead;
    private final ResilienceProperties settings;
    private final Duration defaultBudget;
    private final @Nullable Duration readTimeout;
    private final ProviderCallMetrics metrics;

    /** Without a read timeout every timeout counts as a provider failure. */
    public ResilientCaller(String name, @Nullable CircuitBreaker breaker, Semaphore bulkhead,
                           ResilienceProperties settings, Duration defaultBudget) {
        this(name, breaker, bulkhead, settings, defaultBudget, null);
    }

    /**
     * @param readTimeout the provider client's read timeout; an attempt started with less deadline budget than this
     *                    that then runs out of budget is not counted against the provider (review M7)
     */
    public ResilientCaller(String name, @Nullable CircuitBreaker breaker, Semaphore bulkhead,
                           ResilienceProperties settings, Duration defaultBudget, @Nullable Duration readTimeout) {
        this(name, breaker, bulkhead, settings, defaultBudget, readTimeout, ProviderCallMetrics.noop());
    }

    /** @param metrics timers for {@code ddareungi.provider.calls} of this provider */
    public ResilientCaller(String name, @Nullable CircuitBreaker breaker, Semaphore bulkhead,
                           ResilienceProperties settings, Duration defaultBudget, @Nullable Duration readTimeout,
                           ProviderCallMetrics metrics) {
        this.name = name;
        this.breaker = breaker;
        this.bulkhead = bulkhead;
        this.settings = settings;
        this.defaultBudget = defaultBudget;
        this.readTimeout = readTimeout;
        this.metrics = metrics;
    }

    public @Nullable CircuitBreaker breaker() { return breaker; }

    /**
     * @param idempotent true for GET exchanges, which may be retried once
     * @throws ProviderCallException on timeout, open breaker or full bulkhead
     * @throws RuntimeException the exchange's own failure (e.g. {@link RestClientResponseException}) otherwise
     */
    public <T> T call(Supplier<T> exchange, boolean idempotent) {
        long started = System.nanoTime();
        try {
            T result = guarded(exchange, idempotent);
            metrics.record(ProviderCallOutcome.SUCCESS, System.nanoTime() - started);
            return result;
        } catch (RuntimeException error) {
            metrics.record(ProviderCallOutcome.of(error), System.nanoTime() - started);
            throw error;
        }
    }

    private <T> T guarded(Supplier<T> exchange, boolean idempotent) {
        var deadline = Deadline.within(defaultBudget);
        if (deadline.expired()) throw timeout("deadline already exceeded");
        long permit = breaker == null ? CircuitBreaker.DENIED : breaker.acquire();
        if (breaker != null && permit == CircuitBreaker.DENIED) {
            throw new ProviderCallException(ProviderCallException.Kind.CIRCUIT_OPEN, name + " circuit breaker is open");
        }
        boolean acquired;
        try {
            acquired = bulkhead.tryAcquire(Math.min(settings.bulkhead().maxWait().toNanos(), deadline.remaining().toNanos()),
                    TimeUnit.NANOSECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            if (breaker != null) breaker.onIgnored(permit);
            throw timeout("interrupted while waiting for a bulkhead slot");
        }
        if (!acquired) {
            if (breaker != null) breaker.onIgnored(permit);
            throw new ProviderCallException(ProviderCallException.Kind.BULKHEAD_FULL, name + " bulkhead is full");
        }
        var faults = new Attempts();
        try {
            T result = idempotent ? withRetry(exchange, deadline, faults) : attempt(exchange, deadline, faults);
            if (breaker != null) breaker.onSuccess(permit);
            return result;
        } catch (RuntimeException error) {
            if (breaker != null) {
                // A cancelled request (the whole plan timed out elsewhere) says nothing about the provider, and
                // neither does a timeout that only the remaining request budget caused (M7).
                boolean budgetOnly = error instanceof ProviderCallException call && call.budgetLimited()
                        && !faults.providerFault;
                if (countsAsFailure(error) && !budgetOnly && !Thread.currentThread().isInterrupted()) breaker.onFailure(permit);
                else breaker.onIgnored(permit);
            }
            if (!(error instanceof ProviderCallException) && isTimeout(error)) throw timeout("provider timed out");
            throw error;
        } finally {
            bulkhead.release();
        }
    }

    /** Per-call record of whether any attempt failed for reasons attributable to the provider. */
    private static final class Attempts {
        volatile boolean providerFault;
    }

    private <T> T withRetry(Supplier<T> exchange, Deadline deadline, Attempts faults) {
        var policy = RetryPolicy.builder()
                .maxRetries(settings.retry().maxRetries())
                .delay(settings.retry().delay())
                .predicate(error -> isRetryable(error) && !Thread.currentThread().isInterrupted()
                        && deadline.remaining().compareTo(settings.retry().delay().plus(MIN_ATTEMPT_BUDGET)) > 0)
                .build();
        try {
            return new RetryTemplate(policy).execute(() -> attempt(exchange, deadline, faults));
        } catch (RetryException error) {
            Throwable last = error.getLastException() != null ? error.getLastException() : error.getCause();
            if (last instanceof RuntimeException runtime) throw runtime;
            if (last instanceof Error fatal) throw fatal;
            if (last instanceof InterruptedException) Thread.currentThread().interrupt();
            throw timeout("retry interrupted");
        }
    }

    private <T> T attempt(Supplier<T> exchange, Deadline deadline, Attempts faults) {
        if (deadline.expired()) throw budgetTimeout("deadline exceeded before attempt");
        Duration budget = deadline.remaining();
        // M7: with less budget than the read timeout, running out of budget is our limit, not the provider's.
        boolean budgetShort = readTimeout != null && budget.compareTo(readTimeout) < 0;
        var future = ContextPropagation.start(name + "-call", exchange::get);
        try {
            return future.get(budget.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            if (budgetShort) throw budgetTimeout("deadline exceeded");
            faults.providerFault = true;
            throw timeout("deadline exceeded");
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw timeout("interrupted");
        } catch (ExecutionException error) {
            RuntimeException cause = ContextPropagation.unwrap(error);
            if (countsAsFailure(cause)) faults.providerFault = true;
            throw cause;
        }
    }

    private ProviderCallException budgetTimeout(String detail) {
        return new ProviderCallException(ProviderCallException.Kind.TIMEOUT, name + ": " + detail, true);
    }

    private ProviderCallException timeout(String detail) {
        return new ProviderCallException(ProviderCallException.Kind.TIMEOUT, name + ": " + detail);
    }

    static boolean isRetryable(Throwable error) {
        if (error instanceof ProviderCallException call) return call.kind() == ProviderCallException.Kind.TIMEOUT;
        if (error instanceof RestClientResponseException response) return response.getStatusCode().is5xxServerError();
        return error instanceof ResourceAccessException;
    }

    /** 4xx answers (bad input, quota, credentials) are the caller's problem, not provider health. */
    static boolean countsAsFailure(Throwable error) {
        if (error instanceof RestClientResponseException response) return !response.getStatusCode().is4xxClientError();
        if (error instanceof ProviderCallException call) return call.kind() == ProviderCallException.Kind.TIMEOUT;
        return true;
    }

    static boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ProviderCallException call && call.kind() == ProviderCallException.Kind.TIMEOUT) return true;
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) return true;
            if (cause.getCause() == cause) break;
        }
        return false;
    }
}
