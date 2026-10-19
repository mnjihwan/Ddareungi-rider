package com.ddareungi.station.infrastructure;

import com.ddareungi.station.domain.Location;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryStationSearchAdapterTest {
    @Test void supportsKeyboardInitialsTyposAndWhitespaceInSampleMode() {
        var adapter = new InMemoryStationSearchAdapter();
        var center = new Location(37.4979, 127.0276);
        for (String query : java.util.List.of("rkdska역", "ㄱㄴㅇ", "강남얙", "강 남 역"))
            assertThat(adapter.searchByName(query, center)).as(query)
                    .extracting(com.ddareungi.station.domain.Station::stationId).containsExactly("ST-001");
        assertThat(adapter.searchByName("없는대여소이름", center)).isEmpty();
    }
    @Test void viewportFindsStationsOutsideTheSearchRadius() {
        var adapter = new InMemoryStationSearchAdapter();
        assertThat(adapter.findNearby(new Location(37.4979, 127.0276), 500))
                .extracting(com.ddareungi.station.domain.Station::stationId).doesNotContain("ST-002");
        assertThat(adapter.findInBounds(new com.ddareungi.station.domain.StationBounds(37.5000, 37.5010, 127.0360, 127.0370)))
                .extracting(com.ddareungi.station.domain.Station::stationId).containsExactly("ST-002");
    }
    @Test
    void favoritesRemainAvailableOutsideTheNearbyRadius() {
        var adapter = new InMemoryStationSearchAdapter();
        var farAway = new Location(37.6, 126.9);
        assertThat(adapter.findNearby(farAway, 500)).isEmpty();
        assertThat(adapter.findByIds(java.util.List.of("ST-001", "missing"), farAway))
                .extracting(com.ddareungi.station.domain.Station::stationId).containsExactly("ST-001");
    }

    @Test
    void searchesStationNameAndAddressWithoutCoordinatesFromTheUser() {
        var adapter = new InMemoryStationSearchAdapter();
        var center = new Location(37.4979, 127.0276);
        assertThat(adapter.searchByName("역삼역", center))
                .extracting(com.ddareungi.station.domain.Station::stationId).containsExactly("ST-002");
        assertThat(adapter.searchByName("봉은사로", center))
                .extracting(com.ddareungi.station.domain.Station::stationId).containsExactly("ST-003");
    }

    @Test
    void returnsNearbyStationsInDistanceOrder() {
        var stations = new InMemoryStationSearchAdapter().findNearby(new Location(37.4979, 127.0276), 1500);

        assertThat(stations).isNotEmpty();
        assertThat(stations).isSortedAccordingTo(java.util.Comparator.comparingDouble(it -> it.distanceMeters()));
    }
}
