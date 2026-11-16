package com.ddareungi.global.ratelimit;

import com.ddareungi.global.exception.ApiException;
import com.ddareungi.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RateLimitInterceptorTest {
    final RateLimiter local = mock(RateLimiter.class);
    final RateLimiter shared = mock(RateLimiter.class);

    private RateLimitInterceptor interceptor(boolean enabled) {
        var defaults = RateLimitProperties.defaults();
        var properties = new RateLimitProperties(enabled, null, null, null, defaults.redisFailureCooldown(),
                defaults.localMaxEntries(), defaults.localCleanupInterval());
        return new RateLimitInterceptor(properties, local, shared);
    }

    private static MockHttpServletRequest request(String method, String uri) {
        var request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr("203.0.113.9");
        return request;
    }

    @Test void routesGroupsToTheirLimiterWithHashedClientKey() {
        when(local.tryAcquire(anyString(), any())).thenReturn(RateLimitDecision.ALLOW);
        when(shared.tryAcquire(anyString(), any())).thenReturn(RateLimitDecision.ALLOW);
        var interceptor = interceptor(true);
        String hash = RateLimitInterceptor.clientHash("203.0.113.9");

        assertThat(interceptor.preHandle(request("GET", "/api/v1/stations/nearby"), new MockHttpServletResponse(), new Object())).isTrue();
        verify(local).tryAcquire("stations:" + hash, new RateLimitPolicy(30, 120));
        interceptor.preHandle(request("GET", "/api/v1/places/search"), new MockHttpServletResponse(), new Object());
        verify(shared).tryAcquire("places:" + hash, new RateLimitPolicy(30, 30));
        interceptor.preHandle(request("POST", "/api/v1/routes/plan"), new MockHttpServletResponse(), new Object());
        verify(shared).tryAcquire("routes:" + hash, new RateLimitPolicy(10, 10));
    }

    @Test void clientKeyIsAHashNotTheRawAddress() {
        String hash = RateLimitInterceptor.clientHash("203.0.113.9");
        assertThat(hash).matches("[0-9a-f]{32}").doesNotContain("203");
        assertThat(RateLimitInterceptor.clientHash("203.0.113.10")).isNotEqualTo(hash);
        assertThat(RateLimitInterceptor.clientHash(null)).hasSize(32);
    }

    @Test void rejectionThrowsTheApiExceptionWithRetryAfter() {
        when(shared.tryAcquire(anyString(), any())).thenReturn(new RateLimitDecision(false, 7));
        assertThatThrownBy(() -> interceptor(true).preHandle(request("GET", "/api/v1/places/search"),
                new MockHttpServletResponse(), new Object()))
                .isInstanceOfSatisfying(RateLimitExceededException.class, exception -> {
                    assertThat(exception).isInstanceOf(ApiException.class);
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED);
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(exception.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("7");
                });
        assertThatThrownBy(() -> new RateLimitExceededException(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void optionsDisabledActuatorAndUnknownPathsAreNotCounted() {
        var interceptor = interceptor(true);
        interceptor.preHandle(request("OPTIONS", "/api/v1/places/search"), new MockHttpServletResponse(), new Object());
        interceptor.preHandle(request("GET", "/actuator/health"), new MockHttpServletResponse(), new Object());
        interceptor.preHandle(request("GET", "/api/v1/other"), new MockHttpServletResponse(), new Object());
        interceptor.preHandle(request("GET", "/api/v1/placesX"), new MockHttpServletResponse(), new Object());
        interceptor(false).preHandle(request("GET", "/api/v1/places/search"), new MockHttpServletResponse(), new Object());
        verifyNoInteractions(local, shared);
    }

    @Test void matchedControllerPatternWinsOverTheRawPath() throws Exception {
        when(shared.tryAcquire(anyString(), any())).thenReturn(RateLimitDecision.ALLOW);
        var request = request("GET", "/api/v1/places/search/");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/places/search");
        var handler = new HandlerMethod(new Object(), Object.class.getMethod("toString"));
        interceptor(true).preHandle(request, new MockHttpServletResponse(), handler);
        verify(shared).tryAcquire(startsWith("places:"), eq(new RateLimitPolicy(30, 30)));
        assertThat(RateLimitInterceptor.path(request("GET", "/x/y"), new Object())).isEqualTo("/x/y");
    }

    @Test void classification() {
        assertThat(RateLimitGroup.classify("/api/v1/stations")).isEqualTo(RateLimitGroup.STATIONS);
        assertThat(RateLimitGroup.classify("/api/v1/stations/{stationId}")).isEqualTo(RateLimitGroup.STATIONS);
        assertThat(RateLimitGroup.classify("/api/v1/status/realtime")).isEqualTo(RateLimitGroup.STATIONS);
        assertThat(RateLimitGroup.classify("/api/v1/routes/status")).isEqualTo(RateLimitGroup.STATIONS);
        assertThat(RateLimitGroup.classify("/api/v1/places/nearby-subways")).isEqualTo(RateLimitGroup.PLACES);
        assertThat(RateLimitGroup.classify("/api/v1/routes/plan")).isEqualTo(RateLimitGroup.ROUTES);
        assertThat(RateLimitGroup.classify("/api/v1/routes/search")).isEqualTo(RateLimitGroup.ROUTES);
        assertThat(RateLimitGroup.classify("/actuator/health")).isNull();
        assertThat(RateLimitGroup.classify("/")).isNull();
        assertThat(RateLimitGroup.classify(null)).isNull();
    }

    @Test void propertiesBindWithDefaultsAndOverrides() {
        var bound = new Binder(new MapConfigurationPropertySource(Map.of(
                "ratelimit.places.per-minute", "12",
                "ratelimit.routes.burst", "3")))
                .bindOrCreate("ratelimit", RateLimitProperties.class);
        assertThat(bound.enabled()).isTrue();
        assertThat(bound.policy(RateLimitGroup.STATIONS)).isEqualTo(new RateLimitPolicy(30, 120));
        assertThat(bound.policy(RateLimitGroup.PLACES)).isEqualTo(new RateLimitPolicy(30, 12));
        assertThat(bound.policy(RateLimitGroup.ROUTES)).isEqualTo(new RateLimitPolicy(3, 10));
        assertThat(bound.redisFailureCooldown()).isEqualTo(Duration.ofSeconds(10));
        assertThat(bound.localMaxEntries()).isEqualTo(100_000);
        assertThat(bound.localCleanupInterval()).isEqualTo(Duration.ofSeconds(60));

        var empty = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("ratelimit", RateLimitProperties.class);
        assertThat(empty).isEqualTo(RateLimitProperties.defaults());

        var disabled = new Binder(new MapConfigurationPropertySource(Map.of("ratelimit.enabled", "false")))
                .bindOrCreate("ratelimit", RateLimitProperties.class);
        assertThat(disabled.enabled()).isFalse();

        assertThatThrownBy(() -> new Binder(new MapConfigurationPropertySource(Map.of("ratelimit.places.burst", "0")))
                .bindOrCreate("ratelimit", RateLimitProperties.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
