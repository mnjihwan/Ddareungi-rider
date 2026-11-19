package com.ddareungi.routing.presentation;

import com.ddareungi.station.domain.Location;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Google Encoded Polyline (spec 13.1), checked against the documented reference vector (shared with the web decoder). */
class EncodedPolylineTest {
    static final String GOOGLE = "_p~iF~ps|U_ulLnnqC_mqNvxq`@";
    static final List<Location> GOOGLE_POINTS = List.of(
            new Location(38.5, -120.2), new Location(40.7, -120.95), new Location(43.252, -126.453));

    @Test void encodesTheGoogleReferenceVector() {
        assertThat(EncodedPolyline.encode(GOOGLE_POINTS)).isEqualTo(GOOGLE);
    }

    @Test void decodesTheGoogleReferenceVector() {
        assertThat(EncodedPolyline.decode(GOOGLE)).containsExactlyElementsOf(GOOGLE_POINTS);
    }

    @Test void roundTripsSeoulCoordinatesAtFiveDecimals() {
        var points = List.of(new Location(37.4979, 127.0276), new Location(37.49791, 127.02761),
                new Location(37.5665, 126.978), new Location(37.5665, 126.978));
        assertThat(EncodedPolyline.decode(EncodedPolyline.encode(points))).containsExactlyElementsOf(points);
    }

    @Test void deltasAreTakenBetweenRoundedValuesSoErrorsDoNotAccumulate() {
        // Each point moves by 0.000004 (below the precision); encoding the rounded values keeps every point exact.
        var points = new java.util.ArrayList<Location>();
        for (int i = 0; i < 100; i++) points.add(new Location(37.5 + i * 0.000004, 127.0));
        var decoded = EncodedPolyline.decode(EncodedPolyline.encode(points));
        for (int i = 0; i < points.size(); i++) {
            assertThat(decoded.get(i).latitude()).isCloseTo(points.get(i).latitude(), org.assertj.core.data.Offset.offset(0.000005));
        }
    }

    @Test void roundsSymmetricallyAwayFromZero() {
        // Math.round alone rounds -x.5 towards +∞; the encoder rounds the magnitude, so -v always encodes as -(v).
        for (double value : new double[]{0.0000049, 0.0000051, 1.234565, 37.4979, 126.978, 120.95, 0.00001}) {
            assertThat(EncodedPolyline.scale(-value)).as("%s", value).isEqualTo(-EncodedPolyline.scale(value));
        }
        assertThat(EncodedPolyline.scale(-120.2)).isEqualTo(-12020000);
        assertThat(EncodedPolyline.scale(0.0000051)).isEqualTo(1);
        assertThat(EncodedPolyline.scale(0.0000049)).isZero();
    }

    @Test void zeroDistanceSegmentHasTwoIdenticalPoints() {
        var point = new Location(37.5, 127.0);
        var encoded = EncodedPolyline.encode(List.of(point, point));
        assertThat(EncodedPolyline.decode(encoded)).containsExactly(point, point);
        assertThat(encoded).endsWith("??");
    }

    @Test void emptyAndMalformedInput() {
        assertThat(EncodedPolyline.encode(List.of())).isEmpty();
        assertThat(EncodedPolyline.decode("")).isEmpty();
        assertThatThrownBy(() -> EncodedPolyline.decode("_p~iF~ps|U_")).isInstanceOf(IllegalArgumentException.class);
    }
}
