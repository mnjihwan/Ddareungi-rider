package com.ddareungi.realtime.infrastructure;

import com.ddareungi.realtime.domain.RealtimeStationStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Profile("infra")
@Component
public class SeoulBikeRealtimeClient {
    private final SeoulBikeApiProperties properties;
    private final RestClient restClient;
    private final JsonMapper jsonMapper;

    public SeoulBikeRealtimeClient(SeoulBikeApiProperties properties, RestClient.Builder builder, JsonMapper jsonMapper) {
        this.properties = properties;
        this.restClient = builder.baseUrl(properties.baseUrl()).build();
        this.jsonMapper = jsonMapper;
    }

    public List<RealtimeStationStatus> fetchAll() {
        List<RealtimeStationStatus> result = new ArrayList<>();
        Instant updatedAt = Instant.now();
        for (int page = 0; page < properties.maxPages(); page++) {
            int start = page * properties.pageSize() + 1;
            int end = start + properties.pageSize() - 1;
            String body = restClient.get()
                    .uri("/{key}/json/{service}/{start}/{end}", properties.key(), properties.realtimeService(), start, end)
                    .retrieve().body(String.class);
            List<RealtimeStationStatus> rows = parse(body, updatedAt);
            result.addAll(rows);
            if (rows.size() < properties.pageSize()) break;
        }
        return result;
    }

    private List<RealtimeStationStatus> parse(String body, Instant updatedAt) {
        try {
            JsonNode envelope = jsonMapper.readTree(body).path("rentBikeStatus");
            String code = envelope.path("RESULT").path("CODE").asString();
            if (!"INFO-000".equals(code)) throw new IllegalStateException("bikeList API 오류: " + code);
            List<RealtimeStationStatus> result = new ArrayList<>();
            for (JsonNode row : envelope.path("row")) {
                result.add(new RealtimeStationStatus(
                        row.path("stationId").asString(), row.path("stationName").asString(),
                        row.path("rackTotCnt").asInt(), availableBikes(row),
                        row.path("shared").asDouble(), row.path("stationLatitude").asDouble(),
                        row.path("stationLongitude").asDouble(), updatedAt));
            }
            return result;
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException stateException) throw stateException;
            throw new IllegalStateException("bikeList API 응답을 해석하지 못했습니다.", exception);
        }
    }

    private static int availableBikes(JsonNode row) {
        String value = row.path("parkingBikeTotCnt").asString("");
        if (!value.matches("[0-9]+")) throw new IllegalStateException("Missing or invalid bike count");
        return Integer.parseInt(value);
    }
}
