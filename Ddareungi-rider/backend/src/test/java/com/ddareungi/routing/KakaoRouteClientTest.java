package com.ddareungi.routing;

import com.ddareungi.station.domain.Location;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class KakaoRouteClientTest {
    MockRestServiceServer server;
    KakaoRouteClient client;
    Location from = new Location(37.5, 127.02), to = new Location(37.501, 127.021);
    @BeforeEach void setup() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KakaoRouteClient(builder, JsonMapper.builder().build(), "private-test-key");
    }
    @Test void authenticatesWalkingLongitudeFirstAndPreservesKoreanInstructions() {
        server.expect(requestTo("https://dapi.kakao.com/v2/routing/walk?start_x=127.02&start_y=37.5&end_x=127.021&end_y=37.501&input_coord=WGS84&output_coord=WGS84&route_mode=BROAD_FIRST"))
                .andExpect(header("Authorization", "KakaoAK private-test-key"))
                .andRespond(withSuccess(route("[[127.02,37.5],[127.0205,37.5002],[127.021,37.501]]"), MediaType.APPLICATION_JSON));
        var leg = client.directions("foot-walking", from, to);
        assertThat(leg.mode()).isEqualTo("walk");
        assertThat(leg.path()).hasSize(3).first().isEqualTo(from);
        assertThat(leg.steps().getFirst().instruction()).isEqualTo("강남대로를 따라 이동");
        assertThat(leg.durationSeconds()).isEqualTo(140);
        server.verify();
    }
    @Test void prefersBicycleRoadsAndReusesGeometryOnlyWithinOneRequest() {
        server.expect(twice(), requestTo("https://dapi.kakao.com/v2/routing/bicycle?start_x=127.02&start_y=37.5&end_x=127.021&end_y=37.501&input_coord=WGS84&output_coord=WGS84&route_mode=BIKE_ONLY"))
                .andRespond(withSuccess(route("[[127.02,37.5],[127.021,37.501]]"), MediaType.APPLICATION_JSON));
        var request = client.forRequest();
        assertThat(request.durations("cycling-regular", List.of(from), List.of(to))[0][0]).isEqualTo(140);
        assertThat(request.directions("cycling-regular", from, to).mode()).isEqualTo("bicycle");
        client.forRequest().directions("cycling-regular", from, to);
        server.verify();
    }
    @Test void unreachableRoadIsNullInCandidateComparison() {
        server.expect(anything()).andRespond(withSuccess("{\"status\":\"ROUTE_RESULT_NOT_FOUND\"}", MediaType.APPLICATION_JSON));
        var request = client.forRequest();
        assertThat(request.durations("foot-walking", List.of(from), List.of(to))[0][0]).isNull();
        assertCode(request, "NO_ROAD_ROUTE");
        server.verify();
    }
    @Test void quotaErrorStopsComparisonAndDoesNotExposeProviderBody() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("private-test-key"));
        assertCode(client, "ROUTING_LIMIT");
    }
    @Test void credentialErrorDoesNotExposeProviderBody() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.FORBIDDEN).body("private-test-key"));
        assertCode(client, "ROUTING_AUTH");
    }
    @Test void malformedOrUnrelatedGeometryIsRejected() {
        server.expect(anything()).andRespond(withSuccess(route("[[8,49],[8.1,49.1]]"), MediaType.APPLICATION_JSON));
        assertCode(client, "ROUTING_UNAVAILABLE");
    }
    @Test void missingStatusIsNotAcceptedAsSuccessfulRoute() {
        server.expect(anything()).andRespond(withSuccess("{\"route\":{}}", MediaType.APPLICATION_JSON));
        assertCode(client, "ROUTING_UNAVAILABLE");
    }
    @Test void emptyProviderResponseIsHandledAsAnUpstreamFailure() {
        server.expect(anything()).andRespond(withSuccess());
        assertCode(client, "ROUTING_UNAVAILABLE");
        server.verify();
    }
    @Test void missingKeyMakesNoExternalRequest() {
        var missing = new KakaoRouteClient(RestClient.builder(), JsonMapper.builder().build(), "");
        assertThat(missing.configured()).isFalse();
        assertCode(missing, "ROUTING_NOT_CONFIGURED");
    }
    @Test void identicalEndpointsHaveZeroDurationWithoutAnExternalRequest() {
        var leg = client.directions("foot-walking", from, from);
        assertThat(leg.durationSeconds()).isZero();
        assertThat(leg.path()).containsExactly(from, from);
        server.verify();
    }
    private void assertCode(RouteProvider provider, String code) {
        assertThatThrownBy(() -> provider.directions("foot-walking", from, to))
                .isInstanceOfSatisfying(RouteException.class, error -> {
                    assertThat(error.code).isEqualTo(code);
                    assertThat(error.getMessage()).doesNotContain("private-test-key");
                    assertThat(error.getCause()).isNull();
                });
    }
    private static String route(String points) {
        return """
          {"status":"OK","route":{"properties":{"totalDistance":200,"totalTime":140},"legs":[{
          "steps":[{"properties":{"guidance":"강남대로를 따라 이동","distance":200,"time":140},
          "path":{"points":%s}}]}]}}
          """.formatted(points);
    }
}
