package com.ddareungi.routing;

import com.ddareungi.global.resilience.CircuitBreaker;
import com.ddareungi.global.resilience.Deadline;
import com.ddareungi.global.resilience.ProviderResilience;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.Station;
import com.ddareungi.station.domain.StationSearchPort;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** E4: route timeouts (504 code), GET retry bound, per-segment circuit breakers and the plan deadline. */
class RouteResilienceTest {
    final Location from = new Location(37.5, 127.02), to = new Location(37.501, 127.021);
    final RestClient.Builder builder = RestClient.builder();
    final ProviderResilience resilience = ProviderResilience.defaults();

    @Test void slowKakaoRouteBecomesExternalApiTimeoutThroughTheAdapter() {
        var server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        server.expect(ExpectedCount.manyTimes(), anything()).andRespond(request -> {
            try { Thread.sleep(2_000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return withSuccess("{}", MediaType.APPLICATION_JSON).createResponse(request);
        });
        var walking = new EngineWalkingRouteProvider(new KakaoRouteClient(builder, JsonMapper.builder().build(), "private-test-key", resilience));
        long started = System.nanoTime();
        assertThatThrownBy(() -> Deadline.after(Duration.ofMillis(200)).supply(() -> walking.route(from, to)))
                .isInstanceOfSatisfying(RouteException.class, error -> {
                    assertThat(error.code).isEqualTo("EXTERNAL_API_TIMEOUT");
                    assertThat(error.status).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
                    assertThat(error.getMessage()).doesNotContain("private-test-key");
                });
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1_500));
    }

    @Test void kakaoServerErrorIsRetriedExactlyOnce() {
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.times(2), requestTo(org.hamcrest.Matchers.startsWith("https://dapi.kakao.com/v2/routing/walk?")))
                .andRespond(withServerError().body("private-test-key"));
        var client = new KakaoRouteClient(builder, JsonMapper.builder().build(), "private-test-key", resilience);
        assertThatThrownBy(() -> client.directions("foot-walking", from, to)).isInstanceOfSatisfying(RouteException.class,
                error -> assertThat(error.code).isEqualTo("ROUTING_UNAVAILABLE"));
        server.verify();
    }

    @Test void openWalkingBreakerFailsFastAsWalkProviderErrorWhileCyclingStillWorks() {
        var server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        server.expect(ExpectedCount.times(20), requestTo(org.hamcrest.Matchers.startsWith("https://dapi.kakao.com/v2/routing/walk?")))
                .andRespond(withServerError());
        server.expect(ExpectedCount.once(), requestTo(org.hamcrest.Matchers.startsWith("https://dapi.kakao.com/v2/routing/bicycle?")))
                .andRespond(withSuccess(route(), MediaType.APPLICATION_JSON));
        var engine = new KakaoRouteClient(builder, JsonMapper.builder().build(), "private-test-key", resilience);
        var walking = new EngineWalkingRouteProvider(engine);
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> walking.route(from, to)).isInstanceOfSatisfying(RouteException.class,
                    error -> assertThat(error.code).isEqualTo("WALK_ROUTE_PROVIDER_ERROR"));
        }
        assertThat(resilience.walking().breaker().state()).isEqualTo(CircuitBreaker.State.OPEN);
        // Open: no request is sent (the 20 expected walk calls were all used by the 10 calls + 10 retries).
        assertThatThrownBy(() -> walking.route(from, to)).isInstanceOfSatisfying(RouteException.class, error -> {
            assertThat(error.code).isEqualTo("WALK_ROUTE_PROVIDER_ERROR");
            assertThat(error.status).isEqualTo(HttpStatus.BAD_GATEWAY);
        });
        assertThat(new EngineBicycleRouteProvider(engine).route(from, to).mode()).isEqualTo("bicycle");
        assertThat(resilience.bicycle().breaker().state()).isEqualTo(CircuitBreaker.State.CLOSED);
        server.verify();
    }

    @Test void noRoadRouteAndQuotaAnswersDoNotOpenTheBreaker() {
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.times(15), anything())
                .andRespond(withSuccess("{\"status\":\"ROUTE_RESULT_NOT_FOUND\"}", MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.times(15), anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        var client = new KakaoRouteClient(builder, JsonMapper.builder().build(), "private-test-key", resilience);
        for (int i = 0; i < 15; i++) {
            assertThatThrownBy(() -> client.directions("foot-walking", from, to)).isInstanceOfSatisfying(RouteException.class,
                    error -> assertThat(error.code).isEqualTo("NO_ROAD_ROUTE"));
        }
        for (int i = 0; i < 15; i++) {
            assertThatThrownBy(() -> client.directions("foot-walking", from, to)).isInstanceOfSatisfying(RouteException.class,
                    error -> assertThat(error.code).isEqualTo("ROUTING_LIMIT"));
        }
        assertThat(resilience.walking().breaker().state()).isEqualTo(CircuitBreaker.State.CLOSED);
        server.verify();
    }

    @Test void planDeadlineCancelsSlowProvidersAndAnswersTimeout() throws Exception {
        var stations = mock(StationSearchPort.class);
        var walking = mock(WalkingRouteProvider.class);
        var bicycle = mock(BicycleRouteProvider.class);
        var origin = new Location(37.50, 127.02);
        var destination = new Location(37.52, 127.04);
        when(walking.configured()).thenReturn(true);
        when(bicycle.configured()).thenReturn(true);
        when(walking.forRequest()).thenReturn(walking);
        when(bicycle.forRequest()).thenReturn(bicycle);
        when(stations.findNearby(origin, 1000)).thenReturn(List.of(station("d", new Location(37.5001, 127.02))));
        when(stations.findNearby(destination, 1000)).thenReturn(List.of(station("a", new Location(37.5201, 127.04))));
        var interrupted = new CountDownLatch(1);
        when(walking.durations(anyList(), anyList())).thenAnswer(invocation -> {
            try { Thread.sleep(10_000); } catch (InterruptedException cancelled) { interrupted.countDown(); throw cancelled; }
            return null;
        });
        var planner = new PlanBikeRoute(stations, walking, bicycle, true, Duration.ofMillis(200));
        long started = System.nanoTime();
        assertThatThrownBy(() -> planner.execute(origin, destination, null)).isInstanceOfSatisfying(RouteException.class, error -> {
            assertThat(error.code).isEqualTo("EXTERNAL_API_TIMEOUT");
            assertThat(error.status).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        });
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test void planErrorsInsideTheDeadlineKeepTheirMeaning() {
        var planner = new PlanBikeRoute(mock(StationSearchPort.class), mock(WalkingRouteProvider.class),
                mock(BicycleRouteProvider.class), true);
        assertThatThrownBy(() -> planner.execute(null, to, null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Station station(String id, Location location) {
        return new Station(id, id, "주소", location, 10, 5, 50, Instant.now());
    }

    private String route() {
        return """
          {"status":"OK","route":{"properties":{"totalDistance":200,"totalTime":140},"legs":[{
          "steps":[{"properties":{"guidance":"직진","distance":200,"time":140},
          "path":{"points":[[%s,%s],[%s,%s]]}}]}]}}
          """.formatted(from.longitude(), from.latitude(), to.longitude(), to.latitude());
    }
}
