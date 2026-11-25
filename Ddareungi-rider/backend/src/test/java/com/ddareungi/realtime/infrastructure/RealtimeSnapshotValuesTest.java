package com.ddareungi.realtime.infrastructure;

import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A (0, 0) position is a missing value coerced to numbers; it must never replace the catalog location. */
class RealtimeSnapshotValuesTest {
    private static String versioned(String latitude, String longitude) {
        return "{\"stationId\":\"ST-1\",\"stationName\":\"a\",\"parkingBikeTotCnt\":3,\"stationLatitude\":" + latitude
                + ",\"stationLongitude\":" + longitude + ",\"realtimeUpdatedAt\":\"2026-11-25T10:00:00+09:00\","
                + "\"publicationEligible\":true,\"operationStatus\":\"ACTIVE\"}";
    }

    @Test void zeroZeroPositionIsCorruptInBothLayouts() {
        assertThat(RealtimeSnapshotValues.parseVersioned("ST-1", versioned("0", "0")))
                .isInstanceOf(RealtimeSnapshotValues.Corrupt.class);
        assertThat(RealtimeSnapshotValues.fromLegacyHash("ST-1", Map.of("stationName", "a", "availableBikeCount", "3",
                "latitude", "0.0", "longitude", "0.0", "updatedAt", "2026-11-25T01:00:00Z"))).isEmpty();
    }

    @Test void realPositionsIncludingAZeroComponentStayValid() {
        assertThat(RealtimeSnapshotValues.parseVersioned("ST-1", versioned("37.5", "127.0")))
                .isInstanceOf(RealtimeSnapshotValues.Valid.class);
        assertThat(RealtimeSnapshotValues.parseVersioned("ST-1", versioned("0", "127.0")))
                .isInstanceOf(RealtimeSnapshotValues.Valid.class);
    }
}
