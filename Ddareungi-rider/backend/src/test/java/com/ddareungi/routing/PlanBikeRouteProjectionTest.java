package com.ddareungi.routing;

import com.ddareungi.station.domain.Location;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The return-access projection stays on the first segment of the final walk. */
class PlanBikeRouteProjectionTest {
    final Location start = new Location(37.5000, 127.0000);
    final Location next = new Location(37.5010, 127.0000); // the walk heads north

    @Test void stationBehindTheStartProjectsOntoTheStartNotTheBackwardExtension() {
        var behind = new Location(37.4990, 127.0001);
        assertThat(PlanBikeRoute.projectOntoApproach(behind, start, next)).isEqualTo(start);
    }

    @Test void stationBesideTheSegmentProjectsOntoIt() {
        var beside = new Location(37.5005, 127.0003);
        var projected = PlanBikeRoute.projectOntoApproach(beside, start, next);
        assertThat(projected.latitude()).isCloseTo(37.5005, within(1e-9));
        assertThat(projected.longitude()).isCloseTo(127.0000, within(1e-9));
    }

    @Test void stationPastTheSegmentProjectsOntoItsEnd() {
        var past = new Location(37.5020, 127.0000);
        assertThat(PlanBikeRoute.projectOntoApproach(past, start, next)).isEqualTo(next);
    }
}
