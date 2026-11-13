package com.ddareungi.realtime.infrastructure;

import com.ddareungi.realtime.domain.RealtimeStationStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Parses one stored realtime value into {@link RealtimeStationStatus}. Any missing, malformed or out-of-range
 * field rejects the whole entry ({@link Optional#empty()}): the station is then unknown, never "0 bikes".
 */
final class RealtimeSnapshotValues {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RealtimeSnapshotValues() {}

    /**
     * Versioned layout value (spec v5 21.2, design proposal): JSON with the source API row field names
     * ({@code rackTotCnt}, {@code parkingBikeTotCnt}, {@code shared}, {@code stationLatitude}, ...) plus
     * {@code realtimeUpdatedAt} (ISO-8601 with offset) and internal fields. Numeric fields may be JSON numbers
     * or numeric strings (the Seoul API itself returns strings). {@code publicationEligible=false} hides the
     * entry; {@code snapshotVersion} and {@code operationStatus} are internal and not interpreted here.
     */
    static Optional<RealtimeStationStatus> fromVersionedJson(String stationId, String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            JsonNode node = JSON.readTree(json);
            if (node == null || !node.isObject()) return Optional.empty();
            JsonNode id = node.get("stationId");
            if (id != null && !(id.isString() && stationId.equals(id.stringValue()))) return Optional.empty();
            JsonNode eligible = node.get("publicationEligible");
            if (eligible != null && !eligible.isNull()) {
                if (!eligible.isBoolean() || !eligible.booleanValue()) return Optional.empty();
            }
            JsonNode name = node.get("stationName");
            if (name == null || !name.isString() || name.stringValue().isBlank()) return Optional.empty();
            JsonNode updated = node.get("realtimeUpdatedAt");
            if (updated == null || !updated.isString()) return Optional.empty();
            return build(stationId, name.stringValue(), text(node.get("rackTotCnt")), text(node.get("parkingBikeTotCnt")),
                    text(node.get("shared")), text(node.get("stationLatitude")), text(node.get("stationLongitude")),
                    OffsetDateTime.parse(updated.stringValue()).toInstant());
        } catch (RuntimeException exception) { // includes JacksonException (unchecked in Jackson 3)
            return Optional.empty();
        }
    }

    /** Legacy per-station hash written by {@link RedisRealtimeStationStore#saveAll}. */
    static Optional<RealtimeStationStatus> fromLegacyHash(String stationId, Map<?, ?> values) {
        try {
            Object name = values.get("stationName");
            Object updated = values.get("updatedAt");
            if (name == null || updated == null) return Optional.empty();
            return build(stationId, name.toString(), string(values.get("rackTotalCount")), string(values.get("availableBikeCount")),
                    string(values.get("sharedRate")), string(values.get("latitude")), string(values.get("longitude")),
                    Instant.parse(updated.toString()));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static Optional<RealtimeStationStatus> build(String stationId, String name, String rack, String bikes,
                                                         String shared, String latitude, String longitude, Instant updatedAt) {
        if (rack == null || bikes == null || shared == null || latitude == null || longitude == null) return Optional.empty();
        int rackCount = Integer.parseInt(rack.trim());
        int bikeCount = Integer.parseInt(bikes.trim());
        double sharedRate = Double.parseDouble(shared.trim());
        double lat = Double.parseDouble(latitude.trim());
        double lon = Double.parseDouble(longitude.trim());
        if (rackCount < 0 || bikeCount < 0) return Optional.empty();
        if (!Double.isFinite(sharedRate) || sharedRate < 0) return Optional.empty();
        // Out-of-range coordinates would make station.domain.Location throw and turn a bad cache entry into a 400.
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            return Optional.empty();
        }
        return Optional.of(new RealtimeStationStatus(stationId, name, rackCount, bikeCount, sharedRate, lat, lon, updatedAt));
    }

    /** JSON number or string as text; null/boolean/object/array are rejected (null). */
    private static String text(JsonNode node) {
        if (node == null) return null;
        if (node.isNumber()) return node.asString();
        if (node.isString()) return node.stringValue();
        return null;
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
