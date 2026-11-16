package com.ddareungi.place.application;

import com.ddareungi.station.domain.Location;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceSearchCacheKeyTest {
    @Test void keyIsPrefixedSha256WithoutTheRawQuery() {
        String key = PlaceSearchCache.key("강남역", new Location(37.4979, 127.0276), "keyword");
        assertThat(key).matches("place:v1:[0-9a-f]{64}").doesNotContain("강남역");
    }

    @Test void queryIsNormalizedForCaseAndWhitespace() {
        assertThat(PlaceSearchCache.key("  Gangnam   Station ", null, "keyword"))
                .isEqualTo(PlaceSearchCache.key("gangnam station", null, "keyword"));
        assertThat(PlaceSearchCache.key("gangnam station", null, "keyword"))
                .isNotEqualTo(PlaceSearchCache.key("gangnamstation", null, "keyword"));
    }

    @Test void coordinatesAreRoundedToThreeDecimals() {
        assertThat(PlaceSearchCache.Keys.round(37.49751)).isEqualTo("37.498");
        assertThat(PlaceSearchCache.Keys.round(37.4974)).isEqualTo("37.497");
        assertThat(PlaceSearchCache.Keys.round(127.0)).isEqualTo("127.000");
        assertThat(PlaceSearchCache.Keys.round(null)).isEqualTo("-");
        assertThat(PlaceSearchCache.key("강남역", new Location(37.49751, 127.02761), "keyword"))
                .isEqualTo(PlaceSearchCache.key("강남역", new Location(37.49849, 127.02849), "keyword"))
                .isNotEqualTo(PlaceSearchCache.key("강남역", new Location(37.4985, 127.02849), "keyword"))
                .isNotEqualTo(PlaceSearchCache.key("강남역", null, "keyword"));
    }

    @Test void categoryAndOperationSeparateEntries() {
        assertThat(PlaceSearchCache.key("강남역", null, "keyword"))
                .isNotEqualTo(PlaceSearchCache.key("강남역", null, "keyword:SW8"))
                .isNotEqualTo(PlaceSearchCache.key("강남역", null, "address"));
    }
}
