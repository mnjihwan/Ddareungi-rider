package com.ddareungi.global.resilience;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
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
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(280));
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
}
