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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KakaoRouteMatrixTest {
    MockRestServiceServer server;
    KakaoRouteClient client;
    Location s1 = new Location(37.50, 127.02), s2 = new Location(37.51, 127.03);
    Location d1 = new Location(37.52, 127.04), d2 = new Location(37.53, 127.05);

    @BeforeEach void setup() {
        var builder = RestClient.builder();
        // Matrix cells are requested concurrently, so the arrival order is not deterministic.
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        client = new KakaoRouteClient(builder, JsonMapper.builder().build(), "private-test-key");
    }

    @Test void concurrentMatrixKeepsEachDurationInItsOwnCell() {
        expect(s1, d1, ok(s1, d1, 100));
        expect(s1, d2, ok(s1, d2, 200));
        expect(s2, d1, "{\"status\":\"ROUTE_RESULT_NOT_FOUND\"}");
        expect(s2, d2, ok(s2, d2, 400));
        var request = client.forRequest();
        var matrix = request.durations("cycling-regular", List.of(s1, s2), List.of(d1, d2));
        assertThat(matrix[0][0]).isEqualTo(100);
        assertThat(matrix[0][1]).isEqualTo(200);
        assertThat(matrix[1][0]).isNull();
        assertThat(matrix[1][1]).isEqualTo(400);
        // Geometry of the chosen pair is reused from the per-request cache without another call.
        assertThat(request.directions("cycling-regular", s2, d2).durationSeconds()).isEqualTo(400);
        server.verify();
    }

    @Test void quotaFailureInAnyCellAbortsTheComparison() {
        server.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(),
                        request -> { })
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("private-test-key"));
        assertThatThrownBy(() -> client.forRequest().durations("cycling-regular", List.of(s1, s2), List.of(d1, d2)))
                .isInstanceOfSatisfying(RouteException.class, error -> {
                    assertThat(error.code).isEqualTo("ROUTING_LIMIT");
                    assertThat(error.getMessage()).doesNotContain("private-test-key");
                });
    }

    /** Review H1: 3 × 3 = 9 cycling cells against {@link FakeKakaoRouting}, every answer delayed by 50 ms. */
    private final FakeKakaoRouting fake = new FakeKakaoRouting();
    private final List<Location> sources = List.of(s1, s2, new Location(37.505, 127.025));
    private final List<Location> destinations = List.of(d1, d2, new Location(37.525, 127.045));

    private KakaoRouteClient fakeClient() {
        fake.delayMillis = 50;
        return new KakaoRouteClient(RestClient.builder().requestFactory(fake), JsonMapper.builder().build(), "private-test-key");
    }

    @Test void quotaStopsTheRemainingCellsAndLaterMatricesOfTheSameRequest() {
        var client = fakeClient();
        fake.failing = uri -> true;
        var request = client.forRequest();
        var matrix = request.matrix(RouteProvider.CYCLING, sources, destinations);
        // Without the stop all 9 cells were called; now only the first parallel round (6) can reach the provider.
        assertThat(fake.calls.get()).isLessThanOrEqualTo(KakaoRouteClient.MAX_PARALLEL_CALLS);
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
            assertThat(matrix.cell(i, j).failed()).isTrue();
            assertThat(matrix.cell(i, j).failure().code).isEqualTo("ROUTING_LIMIT");
        }
        int afterFirst = fake.calls.get();
        var walking = request.matrix(RouteProvider.WALKING, sources, destinations);
        assertThat(fake.calls.get()).isEqualTo(afterFirst);
        assertThat(walking.cell(2, 2).failure().code).isEqualTo("ROUTING_LIMIT");
        assertThatThrownBy(() -> request.directions(RouteProvider.CYCLING, s1, d1))
                .isInstanceOfSatisfying(RouteException.class, error -> assertThat(error.code).isEqualTo("ROUTING_LIMIT"));
        assertThat(fake.calls.get()).isEqualTo(afterFirst);
        // The stop belongs to that request only: the next request asks the provider again.
        client.forRequest().matrix(RouteProvider.CYCLING, sources, destinations);
        assertThat(fake.calls.get()).isGreaterThan(afterFirst);
    }

    @Test void rejectedCredentialsStopTheRequestToo() {
        var client = fakeClient();
        fake.failing = uri -> true;
        fake.failure = HttpStatus.UNAUTHORIZED;
        var matrix = client.forRequest().matrix(RouteProvider.CYCLING, sources, destinations);
        assertThat(fake.calls.get()).isLessThanOrEqualTo(KakaoRouteClient.MAX_PARALLEL_CALLS);
        assertThat(matrix.cell(2, 2).failure().code).isEqualTo("ROUTING_AUTH");
    }

    @Test void serverErrorsStayPerCellAndTheOtherCellsGoOn() {
        var client = fakeClient();
        fake.failing = uri -> FakeKakaoRouting.between(uri, s1, d1);
        fake.failure = HttpStatus.INTERNAL_SERVER_ERROR;
        var request = client.forRequest();
        var matrix = request.matrix(RouteProvider.CYCLING, sources, destinations);
        assertThat(matrix.cell(0, 0).failure().code).isEqualTo("ROUTING_UNAVAILABLE");
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++)
            if (i + j > 0) assertThat(matrix.cell(i, j).routed()).as("cell %d,%d", i, j).isTrue();
        // 9 cells + 1 retry of the failing one; nothing was stopped, so a later call still reaches the provider.
        assertThat(fake.calls.get()).isEqualTo(10);
        request.directions(RouteProvider.WALKING, s2, d2);
        assertThat(fake.calls.get()).isEqualTo(11);
    }

    private void expect(Location from, Location to, String body) {
        server.expect(requestTo("https://dapi.kakao.com/v2/routing/bicycle?start_x=" + from.longitude() + "&start_y=" + from.latitude()
                        + "&end_x=" + to.longitude() + "&end_y=" + to.latitude()
                        + "&input_coord=WGS84&output_coord=WGS84&route_mode=BIKE_ONLY"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static String ok(Location from, Location to, int seconds) {
        return """
          {"status":"OK","route":{"properties":{"totalDistance":1000,"totalTime":%d},"legs":[{
          "steps":[{"properties":{"guidance":"직진","distance":1000,"time":%d},
          "path":{"points":[[%s,%s],[%s,%s]]}}]}]}}
          """.formatted(seconds, seconds, from.longitude(), from.latitude(), to.longitude(), to.latitude());
    }
}
