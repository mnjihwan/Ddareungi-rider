package com.ddareungi.global.resilience;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/** Real HTTP against a local stub ({@code com.sun.net.httpserver}): timeouts, retry bound, breaker, bulkhead. */
class ResilientCallerTest {
    HttpServer server;
    final AtomicInteger requests = new AtomicInteger();
    final CountDownLatch release = new CountDownLatch(1);
    RestClient client;
    ResilienceProperties settings = ResilienceProperties.defaults();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            int status = switch (path) {
                case "/500" -> 500;
                case "/429" -> 429;
                case "/400" -> 400;
                default -> 200;
            };
            if (path.equals("/slow")) {
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
        factory.setReadTimeout(Duration.ofMillis(300));
        client = RestClient.builder().requestFactory(factory)
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
    }

    @AfterEach void stop() {
        release.countDown();
        server.stop(0);
    }

    ResilientCaller caller(CircuitBreaker breaker, Duration budget) {
        return new ResilientCaller("test", breaker, new Semaphore(4), settings, budget);
    }

    CircuitBreaker breaker() { return new CircuitBreaker("test", settings.circuitBreaker()); }

    String get(String path) { return client.get().uri(path).retrieve().body(String.class); }

    @Test void readTimeoutIsRetriedOnceThenReportedAsTimeout() {
        var caller = caller(breaker(), Duration.ofSeconds(3));
        long started = System.nanoTime();
        assertThatThrownBy(() -> caller.call(() -> get("/slow"), true)).isInstanceOfSatisfying(ProviderCallException.class,
                error -> assertThat(error.kind()).isEqualTo(ProviderCallException.Kind.TIMEOUT));
        assertThat(requests).hasValue(2); // original + exactly one retry
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test void deadlineCapsTheCallEvenWhenReadTimeoutIsLonger() {
        var caller = caller(breaker(), Duration.ofMillis(100));
        long started = System.nanoTime();
        assertThatThrownBy(() -> caller.call(() -> get("/slow"), true)).isInstanceOfSatisfying(ProviderCallException.class,
                error -> assertThat(error.kind()).isEqualTo(ProviderCallException.Kind.TIMEOUT));
        // Review Low: was 280 ms, which flaked on loaded CI machines (virtual thread start + cancel). 700 ms still
        // proves the 100 ms deadline wins over the 300 ms read timeout plus a retry (>= 600 ms).
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(700));
        assertThat(requests).hasValue(1); // no budget left for a retry
    }

    @Test void currentRequestDeadlineIsTighterThanTheProviderBudget() {
        var caller = caller(breaker(), Duration.ofSeconds(10));
        assertThatThrownBy(() -> Deadline.after(Duration.ofMillis(150)).supply(() -> caller.call(() -> get("/slow"), true)))
                .isInstanceOfSatisfying(ProviderCallException.class,
                        error -> assertThat(error.kind()).isEqualTo(ProviderCallException.Kind.TIMEOUT));
        assertThat(requests).hasValue(1);
    }

    @Test void serverErrorIsRetriedOnceForGetButNotForPost() {
        var caller = caller(breaker(), Duration.ofSeconds(3));
        assertThatThrownBy(() -> caller.call(() -> get("/500"), true)).isInstanceOf(HttpServerErrorException.class);
        assertThat(requests).hasValue(2);
        assertThatThrownBy(() -> caller.call(() -> get("/500"), false)).isInstanceOf(HttpServerErrorException.class);
        assertThat(requests).hasValue(3);
    }

    @Test void clientErrorsAndQuotaAreNeitherRetriedNorCounted() {
        var breaker = breaker();
        var caller = caller(breaker, Duration.ofSeconds(3));
        for (int i = 0; i < 15; i++) {
            assertThatThrownBy(() -> caller.call(() -> get("/429"), true)).isInstanceOf(HttpClientErrorException.class);
            assertThatThrownBy(() -> caller.call(() -> get("/400"), true)).isInstanceOf(HttpClientErrorException.class);
        }
        assertThat(requests).hasValue(30);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test void repeatedServerErrorsOpenTheBreakerAndThenFailFastWithoutRequests() {
        var breaker = breaker();
        var caller = new ResilientCaller("test", breaker, new Semaphore(4),
                new ResilienceProperties(new ResilienceProperties.Retry(0, Duration.ZERO), settings.circuitBreaker(),
                        settings.bulkhead()), Duration.ofSeconds(3));
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> caller.call(() -> get("/500"), true)).isInstanceOf(HttpServerErrorException.class);
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        int before = requests.get();
        assertThatThrownBy(() -> caller.call(() -> get("/ok"), true)).isInstanceOfSatisfying(ProviderCallException.class,
                error -> assertThat(error.kind()).isEqualTo(ProviderCallException.Kind.CIRCUIT_OPEN));
        assertThat(requests).hasValue(before);
    }

    // ---- Review M7: timeouts caused only by the remaining request budget do not count against the provider ----
    static final ResilienceProperties.Breaker SMALL_BREAKER = new ResilienceProperties.Breaker(4, 4, 50, Duration.ofSeconds(30), 1);

    ResilienceProperties noRetry() {
        return new ResilienceProperties(new ResilienceProperties.Retry(0, Duration.ZERO), SMALL_BREAKER, settings.bulkhead());
    }

    static String hang() {
        try { Thread.sleep(10_000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        return "late";
    }

    @Test void timeoutFromABudgetShorterThanTheReadTimeoutIsNotCounted() {
        var breaker = new CircuitBreaker("test", SMALL_BREAKER);
        // Read timeout 300 ms (the stub client's), only 100 ms of budget: the cut is ours, not the provider's.
        var caller = new ResilientCaller("test", breaker, new Semaphore(4), noRetry(), Duration.ofMillis(100), Duration.ofMillis(300));
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> caller.call(() -> get("/slow"), true)).isInstanceOfSatisfying(ProviderCallException.class, error -> {
                assertThat(error.kind()).isEqualTo(ProviderCallException.Kind.TIMEOUT); // still 504 for the client
                assertThat(error.budgetLimited()).isTrue();
            });
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test void timeoutWithABudgetAtLeastTheReadTimeoutStillCounts() {
        var breaker = new CircuitBreaker("test", SMALL_BREAKER);
        // 100 ms of budget, read timeout 50 ms: the provider had its full read timeout and still did not answer.
        var caller = new ResilientCaller("test", breaker, new Semaphore(4), noRetry(), Duration.ofMillis(100), Duration.ofMillis(50));
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> caller.call(ResilientCallerTest::hang, true)).isInstanceOfSatisfying(ProviderCallException.class,
                    error -> assertThat(error.budgetLimited()).isFalse());
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test void budgetLimitedRetryAfterAProviderFailureStillCounts() {
        var breaker = new CircuitBreaker("test", SMALL_BREAKER);
        var retryOnce = new ResilienceProperties(new ResilienceProperties.Retry(1, Duration.ZERO), SMALL_BREAKER, settings.bulkhead());
        var caller = new ResilientCaller("test", breaker, new Semaphore(4), retryOnce, Duration.ofMillis(150), Duration.ofSeconds(10));
        for (int i = 0; i < 4; i++) {
            var attempts = new AtomicInteger();
            // First attempt: connection error (provider fault). Retry: hangs until the short budget runs out.
            assertThatThrownBy(() -> caller.call(() -> {
                if (attempts.incrementAndGet() == 1) throw new ResourceAccessException("connection refused");
                return hang();
            }, true)).isInstanceOfSatisfying(ProviderCallException.class, error -> assertThat(error.budgetLimited()).isTrue());
            assertThat(attempts).hasValue(2);
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test void callAdmittedBeforeTheBreakerOpenedIsNotTakenForTheHalfOpenProbe() throws Exception {
        var now = new java.util.concurrent.atomic.AtomicLong();
        var breaker = new CircuitBreaker("test", SMALL_BREAKER, now::get);
        var caller = new ResilientCaller("test", breaker, new Semaphore(4), noRetry(), Duration.ofSeconds(5));
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var slow = Executors.newVirtualThreadPerTaskExecutor().submit(() -> caller.call(() -> {
            started.countDown();
            try { finish.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "old";
        }, false));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> caller.call(() -> { throw new ResourceAccessException("down"); }, false))
                    .isInstanceOf(ResourceAccessException.class);
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        now.addAndGet(Duration.ofSeconds(30).toNanos());
        long probe = breaker.acquire(); // the real probe is in flight
        assertThat(probe).isNotEqualTo(CircuitBreaker.DENIED);

        finish.countDown();
        assertThat(slow.get(5, TimeUnit.SECONDS)).isEqualTo("old");
        // The old call's success neither closed the breaker nor freed the probe slot.
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(breaker.tryAcquirePermission()).isFalse();
        breaker.onSuccess(probe);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test void fullBulkheadRejectsWithoutCallingTheProvider() {
        var caller = new ResilientCaller("test", breaker(), new Semaphore(0), settings, Duration.ofSeconds(3));
        assertThatThrownBy(() -> caller.call(() -> get("/ok"), true)).isInstanceOfSatisfying(ProviderCallException.class,
                error -> assertThat(error.kind()).isEqualTo(ProviderCallException.Kind.BULKHEAD_FULL));
        assertThat(requests).hasValue(0);
    }

    @Test void successPassesTheBodyAndCarriesTheRequestId() {
        var seen = new AtomicReference<String>();
        MDC.put("requestId", "caller-test");
        try {
            assertThat(caller(breaker(), Duration.ofSeconds(3)).call(() -> {
                seen.set(MDC.get("requestId"));
                return get("/ok");
            }, true)).isEqualTo("{}");
        } finally {
            MDC.remove("requestId");
        }
        assertThat(seen).hasValue("caller-test");
    }

    /** A timed-out exchange that is still running keeps its bulkhead slot until it actually ends. */
    @Test void bulkheadSlotIsHeldUntilTheCancelledExchangeEnds() throws Exception {
        var bulkhead = new Semaphore(1);
        var caller = new ResilientCaller("test", breaker(), bulkhead, noRetry(), Duration.ofMillis(100));
        var finish = new CountDownLatch(1);
        assertThatThrownBy(() -> caller.call(() -> {
            // Ignores interruption, like a socket read that cancellation cannot stop.
            while (true) {
                try { if (finish.await(5, TimeUnit.SECONDS)) return "late"; } catch (InterruptedException ignored) { }
            }
        }, true)).isInstanceOfSatisfying(ProviderCallException.class,
                error -> assertThat(error.kind()).isEqualTo(ProviderCallException.Kind.TIMEOUT));
        assertThat(bulkhead.availablePermits()).isZero();
        assertThatThrownBy(() -> caller.call(() -> "next", true)).isInstanceOfSatisfying(ProviderCallException.class,
                error -> assertThat(error.kind()).isEqualTo(ProviderCallException.Kind.BULKHEAD_FULL));

        finish.countDown();
        long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (bulkhead.availablePermits() == 0 && System.nanoTime() < until) Thread.onSpinWait();
        assertThat(bulkhead.availablePermits()).isEqualTo(1);
        assertThat(caller.call(() -> "next", true)).isEqualTo("next");
    }
}
