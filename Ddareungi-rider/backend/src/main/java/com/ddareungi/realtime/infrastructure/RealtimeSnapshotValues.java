package com.ddareungi.realtime.infrastructure;

import com.ddareungi.realtime.domain.PublicationGate;
import com.ddareungi.realtime.domain.RealtimeStationStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Parses one stored realtime value into {@link RealtimeStationStatus}. A missing or malformed required field
 * (name, available count, coordinates, collection time) rejects the whole entry: the station is then unknown,
 * never "0 bikes". {@code rackTotCnt} and {@code shared} are nullable (spec v5 11.0): absent, null or blank keeps the
 * entry with a null value, while a present but malformed or negative value still rejects it. {@code shared} above 100 is
 * kept as is.
 */
final class RealtimeSnapshotValues {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RealtimeSnapshotValues() {}

    /** Outcome of parsing one versioned value. */
    sealed interface Versioned permits Valid, Excluded, Corrupt {}

    /** Well formed and {@link PublicationGate#ELIGIBLE}. */
    record Valid(RealtimeStationStatus status) implements Versioned {}

    /** A JSON object for this station whose publication gate is not {@link PublicationGate#ELIGIBLE} (D3). */
    record Excluded(PublicationGate gate) implements Versioned {}

    /** Unreadable, for another station, or missing/invalid required fields: unknown. */
    record Corrupt() implements Versioned {}

    private static final Corrupt CORRUPT = new Corrupt();

    /**
     * Versioned layout value (spec v5 21.2, design proposal): JSON with the source API row field names
     * ({@code rackTotCnt}, {@code parkingBikeTotCnt}, {@code shared}, {@code stationLatitude}, ...) plus
     * {@code realtimeUpdatedAt} (ISO-8601 with offset) and internal fields. Numeric fields may be JSON numbers
     * or numeric strings (the Seoul API itself returns strings). The gate ({@code publicationEligible},
     * {@code operationStatus}) is checked before the payload, so an ineligible station is excluded even when its
     * other fields are incomplete. {@code snapshotVersion} is internal and not interpreted here.
     */
    static Versioned parseVersioned(String stationId, String json) {
        if (json == null || json.isBlank()) return CORRUPT;
        try {
            JsonNode node = JSON.readTree(json);
            if (node == null || !node.isObject()) return CORRUPT;
            JsonNode id = node.get("stationId");
            if (id != null && !(id.isString() && stationId.equals(id.stringValue()))) return CORRUPT;
            PublicationGate gate = gate(node);
            if (gate != PublicationGate.ELIGIBLE) return new Excluded(gate);
            JsonNode name = node.get("stationName");
            if (name == null || !name.isString() || name.stringValue().isBlank()) return CORRUPT;
            JsonNode updated = node.get("realtimeUpdatedAt");
            if (updated == null || !updated.isString()) return CORRUPT;
            return build(stationId, name.stringValue(), text(node.get("rackTotCnt")), text(node.get("parkingBikeTotCnt")),
                    text(node.get("shared")), text(node.get("stationLatitude")), text(node.get("stationLongitude")),
                    OffsetDateTime.parse(updated.stringValue()).toInstant())
                    .<Versioned>map(Valid::new).orElse(CORRUPT);
        } catch (RuntimeException exception) { // includes JacksonException (unchecked in Jackson 3)
            return CORRUPT;
        }
    }

    /** Gate of a versioned value: see {@link PublicationGate}. Non-boolean / non-string gate values are INELIGIBLE. */
    static PublicationGate gate(JsonNode node) {
        JsonNode eligible = node.get("publicationEligible");
        PublicationGate eligibleGate = eligible == null || eligible.isNull() ? PublicationGate.MISSING
                : eligible.isBoolean() && eligible.booleanValue() ? PublicationGate.ELIGIBLE : PublicationGate.INELIGIBLE;
        JsonNode status = node.get("operationStatus");
        PublicationGate statusGate = status == null || status.isNull() ? PublicationGate.MISSING
                : status.isString() && "ACTIVE".equals(status.stringValue()) ? PublicationGate.ELIGIBLE : PublicationGate.INELIGIBLE;
        return PublicationGate.of(eligibleGate, statusGate);
    }

    /**
     * Legacy per-station hash written by {@link RedisRealtimeStationStore#saveAll}. The legacy layout carries no
     * publication gate, so only id, name and coordinates (plus count and time) are validated (D3 limitation).
     */
    static Optional<RealtimeStationStatus> fromLegacyHash(String stationId, Map<?, ?> values) {
        try {
            Object name = values.get("stationName");
            Object updated = values.get("updatedAt");
            if (name == null || name.toString().isBlank() || updated == null) return Optional.empty();
            return build(stationId, name.toString(), string(values.get("rackTotalCount")), string(values.get("availableBikeCount")),
                    string(values.get("sharedRate")), string(values.get("latitude")), string(values.get("longitude")),
                    Instant.parse(updated.toString()));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static Optional<RealtimeStationStatus> build(String stationId, String name, String rack, String bikes,
                                                         String shared, String latitude, String longitude, Instant updatedAt) {
        if (stationId == null || stationId.isBlank()) return Optional.empty();
        if (bikes == null || latitude == null || longitude == null) return Optional.empty();
        // Nullable fields: absent, JSON null or blank text means "not provided" (null), never 0.
        Integer rackCount = rack == null || rack.isBlank() ? null : Integer.valueOf(rack.trim());
        int bikeCount = Integer.parseInt(bikes.trim());
        Double sharedRate = shared == null || shared.isBlank() ? null : Double.valueOf(shared.trim());
        double lat = Double.parseDouble(latitude.trim());
        double lon = Double.parseDouble(longitude.trim());
        if ((rackCount != null && rackCount < 0) || bikeCount < 0) return Optional.empty();
        if (sharedRate != null && (!Double.isFinite(sharedRate) || sharedRate < 0)) return Optional.empty();
        // Out-of-range coordinates would make station.domain.Location throw and turn a bad cache entry into a 400.
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            return Optional.empty();
        }
        return Optional.of(new RealtimeStationStatus(stationId, name, rackCount, bikeCount, sharedRate, lat, lon, updatedAt));
    }

    /** JSON number or string as text; null (absent or JSON null) stays null; boolean/object/array are rejected. */
    private static String text(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isNumber()) return node.asString();
        if (node.isString()) return node.stringValue();
        throw new IllegalArgumentException("not a number");
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
