package com.ddareungi.realtime.infrastructure;

import com.ddareungi.realtime.domain.RealtimeStationStatus;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * OA-15493 {@code bikeList} client. Rows are validated one by one: a row with a missing or invalid id, name, bike
 * count or coordinates is skipped (that station stays unknown, never 0 bikes or (0, 0)) instead of failing the whole
 * refresh. The refresh fails only when no valid row was read or most rows were invalid (a format change).
 */
@Profile("infra")
@Component
public class SeoulBikeRealtimeClient {
    private static final Logger log = LoggerFactory.getLogger(SeoulBikeRealtimeClient.class);
    static final String SUCCESS = "INFO-000";
    /** Seoul Open API: "해당하는 데이터가 없습니다" (e.g. a page past the last row). */
    static final String NO_DATA = "INFO-200";

    private final SeoulBikeApiProperties properties;
    private final RestClient restClient;
    private final JsonMapper jsonMapper;

    public SeoulBikeRealtimeClient(SeoulBikeApiProperties properties, RestClient.Builder builder, JsonMapper jsonMapper) {
        this.properties = properties;
        this.restClient = builder.baseUrl(properties.baseUrl()).build();
        this.jsonMapper = jsonMapper;
    }

    private record Page(List<RealtimeStationStatus> rows, int read, int skipped) {}

    public List<RealtimeStationStatus> fetchAll() {
        List<RealtimeStationStatus> result = new ArrayList<>();
        int skipped = 0;
        Instant updatedAt = Instant.now();
        for (int page = 0; page < properties.maxPages(); page++) {
            int start = page * properties.pageSize() + 1;
            int end = start + properties.pageSize() - 1;
            String body = restClient.get()
                    .uri("/{key}/json/{service}/{start}/{end}", properties.key(), properties.realtimeService(), start, end)
                    .retrieve().body(String.class);
            Page parsed = parse(body, updatedAt, page > 0);
            // A station count that is an exact multiple of the page size ends with an empty page (INFO-200).
            if (parsed == null) break;
            result.addAll(parsed.rows());
            skipped += parsed.skipped();
            if (parsed.read() < properties.pageSize()) break;
        }
        if (skipped > 0) {
            if (result.isEmpty() || skipped > result.size()) {
                throw new IllegalStateException("bikeList 응답의 대여소 행 대부분이 올바르지 않습니다: " + skipped + "건");
            }
            log.warn("bikeList: skipped {} invalid station row(s); kept {}", skipped, result.size());
        }
        return result;
    }

    /** @return the page, or null when a page after the first has no data (the end of the list) */
    private @Nullable Page parse(String body, Instant updatedAt, boolean laterPage) {
        JsonNode root;
        try {
            root = jsonMapper.readTree(body);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("bikeList API 응답을 해석하지 못했습니다.", exception);
        }
        JsonNode envelope = root.path("rentBikeStatus");
        String code = envelope.path("RESULT").path("CODE").asString("");
        // An empty result is answered without the service envelope: {"CODE":"INFO-200", ...} or {"RESULT":{...}}.
        if (code.isEmpty()) code = root.path("RESULT").path("CODE").asString(root.path("CODE").asString(""));
        if (laterPage && NO_DATA.equals(code)) return null;
        if (!SUCCESS.equals(code)) throw new IllegalStateException("bikeList API 오류: " + code);
        JsonNode rows = envelope.path("row");
        if (!rows.isArray()) throw new IllegalStateException("bikeList API 응답에 row 배열이 없습니다.");
        List<RealtimeStationStatus> result = new ArrayList<>();
        int skipped = 0;
        for (JsonNode row : rows) {
            RealtimeStationStatus status = station(row, updatedAt);
            if (status == null) skipped++;
            else result.add(status);
        }
        return new Page(result, rows.size(), skipped);
    }

    /** One row, or null when a required field is missing or invalid. */
    private static @Nullable RealtimeStationStatus station(JsonNode row, Instant updatedAt) {
        String id = row.path("stationId").asString("").trim();
        String name = row.path("stationName").asString("").trim();
        Integer bikes = count(row.path("parkingBikeTotCnt"));
        Double latitude = coordinate(row.path("stationLatitude"));
        Double longitude = coordinate(row.path("stationLongitude"));
        if (id.isEmpty() || name.isEmpty() || bikes == null || latitude == null || longitude == null) return null;
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) return null;
        // (0, 0) is how a missing position reads after number coercion; never move a station there.
        if (latitude == 0 && longitude == 0) return null;
        // Primitives for the count and position select the canonical constructor (nullable rack and shared).
        int bikeCount = bikes;
        double lat = latitude, lon = longitude;
        return new RealtimeStationStatus(id, name, optionalInteger(row.path("rackTotCnt")), bikeCount,
                optionalDouble(row.path("shared")), lat, lon, updatedAt);
    }

    /** parkingBikeTotCnt: a non-negative whole number (text or JSON number); anything else is unknown. */
    private static @Nullable Integer count(JsonNode node) {
        String value = node.isMissingNode() || node.isNull() ? "" : node.asString("").trim();
        if (!value.matches("[0-9]{1,9}")) return null;
        return Integer.parseInt(value);
    }

    private static @Nullable Double coordinate(JsonNode node) {
        String value = node.isMissingNode() || node.isNull() ? "" : node.asString("").trim();
        if (value.isEmpty()) return null;
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** rackTotCnt is nullable (spec 11.0): absent, blank or non-integer text is null, never 0. */
    private static @Nullable Integer optionalInteger(JsonNode node) {
        String value = node.isMissingNode() || node.isNull() ? "" : node.asString("").trim();
        return value.matches("[0-9]{1,9}") ? Integer.valueOf(value) : null;
    }

    /** shared is nullable (spec 11.0) and kept as is above 100; absent or non-numeric text is null. */
    private static @Nullable Double optionalDouble(JsonNode node) {
        String value = node.isMissingNode() || node.isNull() ? "" : node.asString("").trim();
        if (value.isEmpty()) return null;
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) && parsed >= 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
