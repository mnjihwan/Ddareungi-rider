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

class EngineBicycleRouteProviderTest {
    RouteProvider engine = mock(RouteProvider.class);
    RouteProvider requestEngine = mock(RouteProvider.class);
    EngineBicycleRouteProvider provider = new EngineBicycleRouteProvider(engine);
    Location from = new Location(37.5, 127.02), to = new Location(37.501, 127.021);

    @Test void delegatesWithCyclingRegularProfileOnTheRequestScopedEngine() {
        when(engine.forRequest()).thenReturn(requestEngine);
        var matrix = new Double[][]{{30.0}};
        var leg = new RouteProvider.Leg("bicycle", 100, 30, List.of(from, to), List.of());
        when(requestEngine.durations("cycling-regular", List.of(from), List.of(to))).thenReturn(matrix);
        when(requestEngine.directions("cycling-regular", from, to)).thenReturn(leg);

        var request = provider.forRequest();
        assertThat(request.durations(List.of(from), List.of(to))).isSameAs(matrix);
        assertThat(request.route(from, to)).isSameAs(leg);
        verify(engine).forRequest();
        verify(engine, never()).durations(anyString(), anyList(), anyList());
        verify(requestEngine, never()).durations(eq("foot-walking"), anyList(), anyList());
        verify(requestEngine, never()).directions(eq("foot-walking"), any(), any());
    }

    @Test void exposesEngineConfigurationAndAttribution() {
        when(engine.configured()).thenReturn(true);
        when(engine.attribution()).thenReturn("openrouteservice · © OpenStreetMap contributors");
        assertThat(provider.configured()).isTrue();
        assertThat(provider.attribution()).isEqualTo("openrouteservice · © OpenStreetMap contributors");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROUTING_UNAVAILABLE", "ROUTING_LIMIT", "ROUTING_AUTH"})
    void providerFailuresBecomeBikeRouteProviderError(String engineCode) {
        var original = new RouteException(HttpStatus.SERVICE_UNAVAILABLE, engineCode, "경로를 가져오지 못했어요.");
        when(engine.directions(anyString(), any(), any())).thenThrow(original);
        when(engine.durations(anyString(), anyList(), anyList())).thenThrow(original);
        for (var call : List.<Runnable>of(() -> provider.route(from, to), () -> provider.durations(List.of(from), List.of(to)))) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(RouteException.class, error -> {
                assertThat(error.code).isEqualTo("BIKE_ROUTE_PROVIDER_ERROR");
                assertThat(error.status).isEqualTo(HttpStatus.BAD_GATEWAY);
                assertThat(error.getMessage()).isEqualTo("경로를 가져오지 못했어요.");
                assertThat(error.getCause()).isSameAs(original);
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_ROAD_ROUTE", "ROUTING_NOT_CONFIGURED"})
    void otherRouteErrorsKeepTheirMeaning(String engineCode) {
        var original = new RouteException(HttpStatus.UNPROCESSABLE_ENTITY, engineCode, "message");
        when(engine.durations(anyString(), anyList(), anyList())).thenThrow(original);
        assertThatThrownBy(() -> provider.durations(List.of(from), List.of(to))).isSameAs(original);
    }

    // End to end through the real engines: credentials never leak into the translated error.
    @Test void kakaoCredentialErrorBecomesBikeProviderErrorWithoutProviderBody() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var kakao = new EngineBicycleRouteProvider(new KakaoRouteClient(builder, JsonMapper.builder().build(), "private-test-key"));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://dapi.kakao.com/v2/routing/bicycle?")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("private-test-key"));
        assertThatThrownBy(() -> kakao.forRequest().durations(List.of(from), List.of(to))).isInstanceOfSatisfying(RouteException.class, error -> {
            assertThat(error.code).isEqualTo("BIKE_ROUTE_PROVIDER_ERROR");
            assertThat(error.getMessage()).doesNotContain("private-test-key");
        });
        server.verify();
    }

    @Test void orsUpstreamFailureBecomesBikeProviderErrorButUnroutableStaysNoRoadRoute() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var ors = new EngineBicycleRouteProvider(new OpenRouteServiceClient(builder, JsonMapper.builder().build(), "private-test-key"));
        server.expect(requestTo("https://api.heigit.org/openrouteservice/v2/directions/cycling-regular/geojson"))
                .andRespond(withServerError().body("private-test-key"));
        server.expect(requestTo("https://api.heigit.org/openrouteservice/v2/directions/cycling-regular/geojson"))
                .andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON).body("{}"));
        assertThatThrownBy(() -> ors.route(from, to)).isInstanceOfSatisfying(RouteException.class, error -> {
            assertThat(error.code).isEqualTo("BIKE_ROUTE_PROVIDER_ERROR");
            assertThat(error.getMessage()).doesNotContain("private-test-key");
        });
        assertThatThrownBy(() -> ors.route(from, to))
                .isInstanceOfSatisfying(RouteException.class, error -> assertThat(error.code).isEqualTo("NO_ROAD_ROUTE"));
        server.verify();
    }
}
