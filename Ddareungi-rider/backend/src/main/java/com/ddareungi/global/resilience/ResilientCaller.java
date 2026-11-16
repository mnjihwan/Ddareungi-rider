package com.ddareungi.global.resilience;

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
 * The attempt should only perform the exchange; parsing the body (e.g. "no road route") stays outside so that
 * successful answers with a domain-level "not found" are never failures.
 */
public final class ResilientCaller {
    /** A retry is only worth starting with at least this much budget left after the delay. */
    private static final Duration MIN_ATTEMPT_BUDGET = Duration.ofMillis(50);

    private final String name;
    private final @Nullable CircuitBreaker breaker;
    private final Semaphore bulkhead;
    private final ResilienceProperties settings;
    private final Duration defaultBudget;

    public ResilientCaller(String name, @Nullable CircuitBreaker breaker, Semaphore bulkhead,
                           ResilienceProperties settings, Duration defaultBudget) {
        this.name = name;
        this.breaker = breaker;
        this.bulkhead = bulkhead;
        this.settings = settings;
        this.defaultBudget = defaultBudget;
    }

    public @Nullable CircuitBreaker breaker() { return breaker; }

    /**
     * @param idempotent true for GET exchanges, which may be retried once
     * @throws ProviderCallException on timeout, open breaker or full bulkhead
     * @throws RuntimeException the exchange's own failure (e.g. {@link RestClientResponseException}) otherwise
     */
    public <T> T call(Supplier<T> exchange, boolean idempotent) {
        var deadline = Deadline.within(defaultBudget);
        if (deadline.expired()) throw timeout("deadline already exceeded");
        if (breaker != null && !breaker.tryAcquirePermission()) {
            throw new ProviderCallException(ProviderCallException.Kind.CIRCUIT_OPEN, name + " circuit breaker is open");
        }
        boolean acquired;
        try {
            acquired = bulkhead.tryAcquire(Math.min(settings.bulkhead().maxWait().toNanos(), deadline.remaining().toNanos()),
                    TimeUnit.NANOSECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            if (breaker != null) breaker.onIgnored();
            throw timeout("interrupted while waiting for a bulkhead slot");
        }
        if (!acquired) {
            if (breaker != null) breaker.onIgnored();
            throw new ProviderCallException(ProviderCallException.Kind.BULKHEAD_FULL, name + " bulkhead is full");
        }
        try {
            T result = idempotent ? withRetry(exchange, deadline) : attempt(exchange, deadline);
            if (breaker != null) breaker.onSuccess();
            return result;
        } catch (RuntimeException error) {
            if (breaker != null) {
                // A cancelled request (the whole plan timed out elsewhere) says nothing about the provider.
                if (countsAsFailure(error) && !Thread.currentThread().isInterrupted()) breaker.onFailure();
                else breaker.onIgnored();
            }
            if (!(error instanceof ProviderCallException) && isTimeout(error)) throw timeout("provider timed out");
            throw error;
        } finally {
            bulkhead.release();
        }
    }

    private <T> T withRetry(Supplier<T> exchange, Deadline deadline) {
        var policy = RetryPolicy.builder()
                .maxRetries(settings.retry().maxRetries())
                .delay(settings.retry().delay())
                .predicate(error -> isRetryable(error) && !Thread.currentThread().isInterrupted()
                        && deadline.remaining().compareTo(settings.retry().delay().plus(MIN_ATTEMPT_BUDGET)) > 0)
                .build();
        try {
            return new RetryTemplate(policy).execute(() -> attempt(exchange, deadline));
        } catch (RetryException error) {
            Throwable last = error.getLastException() != null ? error.getLastException() : error.getCause();
            if (last instanceof RuntimeException runtime) throw runtime;
            if (last instanceof Error fatal) throw fatal;
            if (last instanceof InterruptedException) Thread.currentThread().interrupt();
            throw timeout("retry interrupted");
        }
    }

    private <T> T attempt(Supplier<T> exchange, Deadline deadline) {
        if (deadline.expired()) throw timeout("deadline exceeded before attempt");
        var future = ContextPropagation.start(name + "-call", exchange::get);
        try {
            return future.get(deadline.remaining().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            throw timeout("deadline exceeded");
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw timeout("interrupted");
        } catch (ExecutionException error) {
            throw ContextPropagation.unwrap(error);
        }
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
