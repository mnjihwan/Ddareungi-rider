package com.ddareungi.global.http;

import com.ddareungi.place.PlaceSearchController;
import com.ddareungi.routing.KakaoRouteClient;
import com.ddareungi.station.domain.StationSearchPort;
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

class KakaoHttpClientPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class);
    private HttpServer server;
    private final CountDownLatch release = new CountDownLatch(1);

    @EnableConfigurationProperties(KakaoHttpClientProperties.class)
    static class PropertiesConfig {}

    @AfterEach void stopServer() {
        release.countDown();
        if (server != null) server.stop(0);
    }

    @Test void defaultsAreShortKakaoTimeouts() {
        runner.run(context -> {
            var properties = context.getBean(KakaoHttpClientProperties.class);
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test void timeoutsCanBeOverridden() {
        runner.withPropertyValues("kakao.http.connect-timeout=750ms", "kakao.http.read-timeout=3s").run(context -> {
            var properties = context.getBean(KakaoHttpClientProperties.class);
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofMillis(750));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(3));
        });
    }

    @Test void nonPositiveTimeoutFailsStartup() {
        runner.withPropertyValues("kakao.http.read-timeout=0s").run(context -> assertThat(context).hasFailed());
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
        var properties = new KakaoHttpClientProperties(Duration.ofSeconds(1), Duration.ofMillis(300));
        var client = RestClient.builder().requestFactory(properties.requestFactory())
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();

        long started = System.nanoTime();
        assertThatThrownBy(() -> client.get().uri("/slow").retrieve().body(String.class))
                .isInstanceOf(ResourceAccessException.class)
                .hasRootCauseInstanceOf(HttpTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }

    @Test void productionConstructorsInstallKakaoRequestFactory() {
        var properties = new KakaoHttpClientProperties(Duration.ofSeconds(2), Duration.ofSeconds(5));
        var mapper = JsonMapper.builder().build();

        var routeBuilder = mock(RestClient.Builder.class, RETURNS_SELF);
        when(routeBuilder.build()).thenReturn(mock(RestClient.class));
        new KakaoRouteClient(routeBuilder, mapper, "key", properties);
        verify(routeBuilder).requestFactory(any(JdkClientHttpRequestFactory.class));
        verify(routeBuilder).baseUrl("https://dapi.kakao.com");

        var placeBuilder = mock(RestClient.Builder.class, RETURNS_SELF);
        when(placeBuilder.build()).thenReturn(mock(RestClient.class));
        new PlaceSearchController(placeBuilder, mapper, "key", mock(StationSearchPort.class), properties);
        verify(placeBuilder).requestFactory(any(JdkClientHttpRequestFactory.class));
        verify(placeBuilder).baseUrl(anyString());
    }
}
