package com.ddareungi.realtime.infrastructure;

import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SeoulBikeRealtimeClientTest {

    @org.junit.jupiter.api.Test void rejectsUnavailableCountsInsteadOfInventingZero() {
        for (String field : java.util.List.of("", "\"parkingBikeTotCnt\":null,", "\"parkingBikeTotCnt\":\"\",",
                "\"parkingBikeTotCnt\":\"unknown\",", "\"parkingBikeTotCnt\":-1,", "\"parkingBikeTotCnt\":1.5,")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> readCounts(field))
                    .as(field).isInstanceOf(IllegalStateException.class);
        }
    }

    @org.junit.jupiter.api.Test void preservesActualZeroAndNumericCounts() {
        org.assertj.core.api.Assertions.assertThat(readCounts("\"parkingBikeTotCnt\":\"0\"," )).isZero();
        org.assertj.core.api.Assertions.assertThat(readCounts("\"parkingBikeTotCnt\":0," )).isZero();
        org.assertj.core.api.Assertions.assertThat(readCounts("\"parkingBikeTotCnt\":9," )).isEqualTo(9);
    }

    private String body(String field) {
        return "{\"rentBikeStatus\":{\"RESULT\":{\"CODE\":\"INFO-000\"},\"row\":[{" + field +
                "\"stationId\":\"ST-1\",\"stationName\":\"강남역\",\"rackTotCnt\":\"20\",\"shared\":\"0\",\"stationLatitude\":\"37.5\",\"stationLongitude\":\"127\"}]}}";
    }

    private int readCounts(String field) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new SeoulBikeRealtimeClient(new SeoulBikeApiProperties("http://localhost", "test", 1000, 1, "bikeList", ""),
                builder, JsonMapper.builder().build());
        server.expect(anything()).andRespond(withSuccess(body(field), MediaType.APPLICATION_JSON));
        try { return client.fetchAll().getFirst().availableBikeCount(); }
        finally { server.verify(); }
    }
}
