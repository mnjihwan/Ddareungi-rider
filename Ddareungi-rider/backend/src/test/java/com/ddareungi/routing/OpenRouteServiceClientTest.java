package com.ddareungi.routing;

import com.ddareungi.station.domain.Location;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OpenRouteServiceClientTest {
    MockRestServiceServer server; OpenRouteServiceClient client;
    Location from=new Location(37.5,127.02),to=new Location(37.501,127.021);
    @BeforeEach void setup() {
        var builder=RestClient.builder();server=MockRestServiceServer.bindTo(builder).build();
        client=new OpenRouteServiceClient(builder,JsonMapper.builder().build(),"private-test-key");
    }
    @Test void usesCurrentEndpointServerSideAuthLongitudeFirstAndKoreanWalkingInstructions() {
        server.expect(requestTo("https://api.heigit.org/openrouteservice/v2/directions/foot-walking/geojson"))
            .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization","private-test-key"))
            .andExpect(content().json("""
                {"coordinates":[[127.02,37.5],[127.021,37.501]],"language":"en","instructions":true,"radiuses":[50,50]}
                """))
            .andRespond(withSuccess(route("[[127.02,37.5],[127.0205,37.5002],[127.021,37.501]]"),MediaType.APPLICATION_JSON));
        var leg=client.directions("foot-walking",from,to);
        assertThat(leg.mode()).isEqualTo("walk");assertThat(leg.path()).hasSize(3);
        assertThat(leg.path().getFirst()).isEqualTo(from);assertThat(leg.steps().getFirst().instruction()).isEqualTo("강남대로 · 직진하세요");
        assertThat(leg.distanceMeters()).isEqualTo(200); server.verify();
    }
    @Test void matrixNullMeansUnreachableAndIndicesAreCorrect() {
        server.expect(requestTo("https://api.heigit.org/openrouteservice/v2/matrix/cycling-regular"))
            .andExpect(content().json("""
                {"locations":[[127.02,37.5],[127.021,37.501]],"sources":["0"],"destinations":["1"],"metrics":["duration"]}
                """))
            .andRespond(withSuccess("{\"durations\":[[null]]}",MediaType.APPLICATION_JSON));
        assertThat(client.durations("cycling-regular",List.of(from),List.of(to))[0][0]).isNull();server.verify();
    }
    @Test void malformedMatrixAndInvalidGeometryAreNotAccepted() {
        server.expect(anything()).andRespond(withSuccess("{\"durations\":[[-1]]}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->client.durations("foot-walking",List.of(from),List.of(to))).isInstanceOf(RouteException.class);
    }
    @Test void refusesRouteGeometryFarFromRequestedEndpoints() {
        server.expect(anything()).andRespond(withSuccess(route("[[8,49],[8.1,49.1]]"),MediaType.APPLICATION_JSON));
        assertSafeFailure();
    }
    @Test void providerCredentialErrorNeverExposesBodyOrCause() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("private-test-key"));
        assertSafeFailure();
    }
    @Test void noRoadResponseIsActionable() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.NOT_FOUND).body("private-test-key"));
        assertThatThrownBy(()->client.directions("foot-walking",from,to)).isInstanceOfSatisfying(RouteException.class,
            e->assertThat(e.code).isEqualTo("NO_ROAD_ROUTE"));
    }
    @Test void quotaFailureIsHandledWithoutRetriedProviderCalls() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(()->client.directions("foot-walking",from,to)).isInstanceOfSatisfying(RouteException.class,
            e->assertThat(e.code).isEqualTo("ROUTING_LIMIT"));server.verify();
    }
    @Test void missingKeyMakesNoExternalRequest() {
        var missing=new OpenRouteServiceClient(RestClient.builder(),JsonMapper.builder().build(),"");
        assertThat(missing.configured()).isFalse();assertThatThrownBy(()->missing.directions("foot-walking",from,to))
            .isInstanceOfSatisfying(RouteException.class,e->assertThat(e.code).isEqualTo("ROUTING_NOT_CONFIGURED"));
    }
    private void assertSafeFailure() {
        assertThatThrownBy(()->client.directions("foot-walking",from,to)).isInstanceOfSatisfying(RouteException.class,
            e->{assertThat(e.getMessage()).doesNotContain("private-test-key");assertThat(e.getCause()).isNull();});
    }
    private static String route(String coordinates) {
        return """
          {"features":[{"geometry":{"type":"LineString","coordinates":%s},"properties":{"summary":{"distance":200,"duration":140},
            "segments":[{"steps":[{"instruction":"Continue straight","name":"강남대로","type":6,"distance":200,"duration":140}]}]}}]}
          """.formatted(coordinates);
    }
}
