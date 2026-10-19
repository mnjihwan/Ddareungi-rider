package com.ddareungi.station.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StationBoundsTest {
    @Test void includesCornersAndExcludesOutsideViewport() {
        var bounds = new StationBounds(37.49, 37.50, 127.02, 127.03);
        assertThat(bounds.contains(new Location(37.49, 127.02))).isTrue();
        assertThat(bounds.contains(new Location(37.50, 127.03))).isTrue();
        assertThat(bounds.contains(new Location(37.51, 127.03))).isFalse();
        assertThat(bounds.center().latitude()).isCloseTo(37.495, within(.000001));
    }
    @Test void rejectsInvalidReversedAndExcessiveBounds() {
        assertThatThrownBy(() -> new StationBounds(Double.NaN, 37.5, 127, 127.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StationBounds(37.5, 37.4, 127, 127.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StationBounds(37, 39, 127, 127.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StationBounds(37.4, 37.5, 128, 127)).isInstanceOf(IllegalArgumentException.class);
    }
}
