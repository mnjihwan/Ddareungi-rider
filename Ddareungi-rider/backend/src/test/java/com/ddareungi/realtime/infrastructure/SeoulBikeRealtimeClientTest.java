package com.ddareungi.realtime.infrastructure;

import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
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

    /** P2-1 (spec 11.0): rackTotCnt/shared are nullable, never invented as 0; shared above 100 is kept. */
    @org.junit.jupiter.api.Test void missingRackAndSharedStayNull() {
        String row = "{\"rentBikeStatus\":{\"RESULT\":{\"CODE\":\"INFO-000\"},\"row\":["
                + "{\"stationId\":\"ST-1\",\"stationName\":\"a\",\"parkingBikeTotCnt\":\"3\",\"stationLatitude\":\"37.5\",\"stationLongitude\":\"127\"},"
                + "{\"stationId\":\"ST-2\",\"stationName\":\"b\",\"parkingBikeTotCnt\":\"3\",\"rackTotCnt\":\"\",\"shared\":\"x\",\"stationLatitude\":\"37.5\",\"stationLongitude\":\"127\"},"
                + "{\"stationId\":\"ST-3\",\"stationName\":\"c\",\"parkingBikeTotCnt\":\"3\",\"rackTotCnt\":\"10\",\"shared\":\"130\",\"stationLatitude\":\"37.5\",\"stationLongitude\":\"127\"}]}}";
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new SeoulBikeRealtimeClient(new SeoulBikeApiProperties("http://localhost", "test", 1000, 1, "bikeList", ""),
                builder, JsonMapper.builder().build());
        server.expect(anything()).andRespond(withSuccess(row, MediaType.APPLICATION_JSON));
        var statuses = client.fetchAll();
        org.assertj.core.api.Assertions.assertThat(statuses.get(0).rackTotalCount()).isNull();
        org.assertj.core.api.Assertions.assertThat(statuses.get(0).sharedRate()).isNull();
        org.assertj.core.api.Assertions.assertThat(statuses.get(1).rackTotalCount()).isNull();
        org.assertj.core.api.Assertions.assertThat(statuses.get(1).sharedRate()).isNull();
        org.assertj.core.api.Assertions.assertThat(statuses.get(2).rackTotalCount()).isEqualTo(10);
        org.assertj.core.api.Assertions.assertThat(statuses.get(2).sharedRate()).isEqualTo(130.0);
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

    private static String row(String id, String count, String latitude, String longitude) {
        return "{\"stationId\":\"" + id + "\",\"stationName\":\"n\",\"parkingBikeTotCnt\":" + count
                + ",\"stationLatitude\":" + latitude + ",\"stationLongitude\":" + longitude + "}";
    }

    private static String page(String... rows) {
        return "{\"rentBikeStatus\":{\"RESULT\":{\"CODE\":\"INFO-000\"},\"row\":[" + String.join(",", rows) + "]}}";
    }

    private static SeoulBikeRealtimeClient client(RestClient.Builder builder, int pageSize, int maxPages) {
        return new SeoulBikeRealtimeClient(new SeoulBikeApiProperties("http://localhost", "test", pageSize, maxPages, "bikeList", ""),
                builder, JsonMapper.builder().build());
    }

    /** One bad row is skipped (that station stays unknown) instead of failing the refresh of every station. */
    @org.junit.jupiter.api.Test void invalidRowsAreSkippedAndTheRestIsKept() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withSuccess(page(
                row("ST-1", "\"3\"", "\"37.5\"", "\"127\""),
                row("ST-2", "\"\"", "\"37.5\"", "\"127\""),        // count missing
                row("ST-3", "\"4\"", "\"\"", "\"\""),              // coordinates missing
                row("ST-4", "\"5\"", "\"0\"", "\"0\""),            // (0, 0)
                row("ST-5", "\"6\"", "\"37.6\"", "\"127.1\""),
                row("ST-6", "\"7\"", "\"37.7\"", "\"127.2\""),
                row("ST-7", "\"8\"", "\"37.8\"", "\"127.3\"")), MediaType.APPLICATION_JSON));
        var statuses = client(builder, 1000, 1).fetchAll();
        org.assertj.core.api.Assertions.assertThat(statuses).extracting(s -> s.stationId())
                .containsExactly("ST-1", "ST-5", "ST-6", "ST-7");
        server.verify();
    }

    @org.junit.jupiter.api.Test void mostlyInvalidRowsStillFailTheRefresh() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withSuccess(page(
                row("ST-1", "\"3\"", "\"37.5\"", "\"127\""),
                row("ST-2", "\"\"", "\"37.5\"", "\"127\""),
                row("ST-3", "\"x\"", "\"37.5\"", "\"127\"")), MediaType.APPLICATION_JSON));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client(builder, 1000, 1).fetchAll())
                .isInstanceOf(IllegalStateException.class);
    }

    /** Station count that is an exact multiple of the page size: the next page answers INFO-200, the end of data. */
    @org.junit.jupiter.api.Test void emptyPageAfterAFullPageEndsTheListInsteadOfFailing() {
        for (String empty : java.util.List.of("{\"CODE\":\"INFO-200\",\"MESSAGE\":\"none\"}",
                "{\"RESULT\":{\"CODE\":\"INFO-200\",\"MESSAGE\":\"none\"}}",
                "{\"rentBikeStatus\":{\"RESULT\":{\"CODE\":\"INFO-200\"}}}")) {
            var builder = RestClient.builder();
            var server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("http://localhost/test/json/bikeList/1/2")).andRespond(withSuccess(page(
                    row("ST-1", "\"3\"", "\"37.5\"", "\"127\""), row("ST-2", "\"4\"", "\"37.6\"", "\"127.1\"")),
                    MediaType.APPLICATION_JSON));
            server.expect(requestTo("http://localhost/test/json/bikeList/3/4")).andRespond(withSuccess(empty, MediaType.APPLICATION_JSON));
            org.assertj.core.api.Assertions.assertThat(client(builder, 2, 5).fetchAll()).as(empty).hasSize(2);
            server.verify();
        }
    }

    @org.junit.jupiter.api.Test void noDataOnTheFirstPageIsStillAnError() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withSuccess("{\"CODE\":\"INFO-200\"}", MediaType.APPLICATION_JSON));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client(builder, 1000, 1).fetchAll())
                .isInstanceOf(IllegalStateException.class);
    }
}
