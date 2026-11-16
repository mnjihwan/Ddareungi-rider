package com.ddareungi.global.http;

import com.ddareungi.global.resilience.ProviderResilience;
import com.ddareungi.place.infrastructure.KakaoPlaceSearchProvider;
import com.ddareungi.routing.KakaoRouteClient;
import com.ddareungi.routing.OpenRouteServiceClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ProviderHttpPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class);
    private HttpServer server;
    private final CountDownLatch release = new CountDownLatch(1);

    @EnableConfigurationProperties(ProviderHttpProperties.class)
    static class PropertiesConfig {}

    @AfterEach void stopServer() {
        release.countDown();
        if (server != null) server.stop(0);
    }

    @Test void defaultsFollowD8PerProvider() {
        runner.run(context -> {
            var properties = context.getBean(ProviderHttpProperties.class);
            assertThat(properties.place().connectTimeout()).isEqualTo(Duration.ofSeconds(1));
            assertThat(properties.place().readTimeout()).isEqualTo(Duration.ofMillis(2500));
            assertThat(properties.place().totalTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.route().connectTimeout()).isEqualTo(Duration.ofSeconds(1));
            assertThat(properties.route().readTimeout()).isEqualTo(Duration.ofSeconds(4));
            assertThat(properties.route().totalTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.ors().connectTimeout()).isEqualTo(Duration.ofSeconds(1));
            assertThat(properties.ors().readTimeout()).isEqualTo(Duration.ofSeconds(4));
        });
        assertThat(ProviderHttpProperties.defaults().place().totalTimeout()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test void timeoutsCanBeOverridden() {
        runner.withPropertyValues("provider.http.place.connect-timeout=750ms", "provider.http.place.read-timeout=3s",
                "provider.http.route.total-timeout=8s", "provider.http.ors.read-timeout=6s").run(context -> {
            var properties = context.getBean(ProviderHttpProperties.class);
            assertThat(properties.place().connectTimeout()).isEqualTo(Duration.ofMillis(750));
            assertThat(properties.place().readTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.route().totalTimeout()).isEqualTo(Duration.ofSeconds(8));
            assertThat(properties.ors().readTimeout()).isEqualTo(Duration.ofSeconds(6));
        });
    }

    @Test void nonPositiveTimeoutFailsStartup() {
        runner.withPropertyValues("provider.http.place.read-timeout=0s").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("provider.http.route.total-timeout=-1s").run(context -> assertThat(context).hasFailed());
    }

    @Test void slowProviderResponseIsCutOffByReadTimeout() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/slow", exchange -> {
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var timeouts = new ProviderHttpProperties.Place(Duration.ofSeconds(1), Duration.ofMillis(300), Duration.ofSeconds(3));
        var client = RestClient.builder().requestFactory(timeouts.requestFactory())
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();

        long started = System.nanoTime();
        assertThatThrownBy(() -> client.get().uri("/slow").retrieve().body(String.class))
                .isInstanceOf(ResourceAccessException.class)
                .hasRootCauseInstanceOf(HttpTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }

    @Test void productionConstructorsInstallProviderRequestFactories() {
        var properties = ProviderHttpProperties.defaults();
        var resilience = ProviderResilience.defaults();
        var mapper = JsonMapper.builder().build();

        var routeBuilder = mock(RestClient.Builder.class, RETURNS_SELF);
        when(routeBuilder.build()).thenReturn(mock(RestClient.class));
        new KakaoRouteClient(routeBuilder, mapper, "key", properties, resilience);
        verify(routeBuilder).requestFactory(any(JdkClientHttpRequestFactory.class));
        verify(routeBuilder).baseUrl("https://dapi.kakao.com");

        var placeBuilder = mock(RestClient.Builder.class, RETURNS_SELF);
        when(placeBuilder.build()).thenReturn(mock(RestClient.class));
        new KakaoPlaceSearchProvider(placeBuilder, mapper, "key", properties, resilience);
        verify(placeBuilder).requestFactory(any(JdkClientHttpRequestFactory.class));
        verify(placeBuilder).baseUrl(anyString());

        var orsBuilder = mock(RestClient.Builder.class, RETURNS_SELF);
        when(orsBuilder.build()).thenReturn(mock(RestClient.class));
        new OpenRouteServiceClient(orsBuilder, mapper, "key", properties, resilience);
        verify(orsBuilder).requestFactory(any(JdkClientHttpRequestFactory.class));
    }
}
