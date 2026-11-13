package com.ddareungi.routing;

import com.ddareungi.station.domain.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class EngineWalkingRouteProviderTest {
    RouteProvider engine = mock(RouteProvider.class);
    RouteProvider requestEngine = mock(RouteProvider.class);
    EngineWalkingRouteProvider provider = new EngineWalkingRouteProvider(engine);
    Location from = new Location(37.5, 127.02), to = new Location(37.501, 127.021);

    @Test void delegatesWithFootWalkingProfileOnTheRequestScopedEngine() {
        when(engine.forRequest()).thenReturn(requestEngine);
        var matrix = new Double[][]{{30.0}};
        var leg = new RouteProvider.Leg("walk", 100, 30, List.of(from, to), List.of());
        when(requestEngine.durations("foot-walking", List.of(from), List.of(to))).thenReturn(matrix);
        when(requestEngine.directions("foot-walking", from, to)).thenReturn(leg);

        var request = provider.forRequest();
        assertThat(request.durations(List.of(from), List.of(to))).isSameAs(matrix);
        assertThat(request.route(from, to)).isSameAs(leg);
        verify(engine).forRequest();
        verify(engine, never()).durations(anyString(), anyList(), anyList());
        verify(requestEngine, never()).durations(eq("cycling-regular"), anyList(), anyList());
        verify(requestEngine, never()).directions(eq("cycling-regular"), any(), any());
    }

    @Test void exposesEngineConfigurationAndAttribution() {
        when(engine.configured()).thenReturn(true);
        when(engine.attribution()).thenReturn("경로 제공 · Kakao Maps");
        assertThat(provider.configured()).isTrue();
        assertThat(provider.attribution()).isEqualTo("경로 제공 · Kakao Maps");
        when(engine.configured()).thenReturn(false);
        assertThat(provider.configured()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROUTING_UNAVAILABLE", "ROUTING_LIMIT", "ROUTING_AUTH"})
    void providerFailuresBecomeWalkRouteProviderError(String engineCode) {
        var original = new RouteException(HttpStatus.SERVICE_UNAVAILABLE, engineCode, "길찾기 이용량이 많아요.");
        when(engine.directions(anyString(), any(), any())).thenThrow(original);
        when(engine.durations(anyString(), anyList(), anyList())).thenThrow(original);
        for (var call : List.<Runnable>of(() -> provider.route(from, to), () -> provider.durations(List.of(from), List.of(to)))) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(RouteException.class, error -> {
                assertThat(error.code).isEqualTo("WALK_ROUTE_PROVIDER_ERROR");
                assertThat(error.status).isEqualTo(HttpStatus.BAD_GATEWAY);
                assertThat(error.getMessage()).isEqualTo("길찾기 이용량이 많아요.");
                assertThat(error.getCause()).isSameAs(original);
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_ROAD_ROUTE", "ROUTING_NOT_CONFIGURED"})
    void otherRouteErrorsKeepTheirMeaning(String engineCode) {
        var original = new RouteException(HttpStatus.UNPROCESSABLE_ENTITY, engineCode, "message");
        when(engine.directions(anyString(), any(), any())).thenThrow(original);
        assertThatThrownBy(() -> provider.route(from, to)).isSameAs(original);
    }

    @Test void nonRouteErrorsAreNotRewritten() {
        var original = new IllegalArgumentException("지원하지 않는 이동 방식입니다.");
        when(engine.directions(anyString(), any(), any())).thenThrow(original);
        assertThatThrownBy(() -> provider.route(from, to)).isSameAs(original);
    }

    // End to end through the real Kakao engine: no key leaks and the per-request cache still applies.
    @Test void kakaoQuotaErrorBecomesWalkProviderErrorWithoutProviderBody() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var kakao = new EngineWalkingRouteProvider(new KakaoRouteClient(builder, JsonMapper.builder().build(), "private-test-key"));
        server.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("private-test-key"));
        assertThatThrownBy(() -> kakao.forRequest().route(from, to)).isInstanceOfSatisfying(RouteException.class, error -> {
            assertThat(error.code).isEqualTo("WALK_ROUTE_PROVIDER_ERROR");
            assertThat(error.status).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(error.getMessage()).doesNotContain("private-test-key");
        });
        server.verify();
    }

    @Test void kakaoUnreachableWalkStaysNoRoadRouteAndNullInMatrix() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var kakao = new EngineWalkingRouteProvider(new KakaoRouteClient(builder, JsonMapper.builder().build(), "private-test-key"));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://dapi.kakao.com/v2/routing/walk?")))
                .andRespond(withSuccess("{\"status\":\"ROUTE_RESULT_NOT_FOUND\"}", MediaType.APPLICATION_JSON));
        var request = kakao.forRequest();
        assertThat(request.durations(List.of(from), List.of(to))[0][0]).isNull();
        // Cached within the request: the second lookup makes no extra provider call.
        assertThatThrownBy(() -> request.route(from, to))
                .isInstanceOfSatisfying(RouteException.class, error -> assertThat(error.code).isEqualTo("NO_ROAD_ROUTE"));
        server.verify();
    }
}
