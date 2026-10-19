package com.ddareungi.station.infrastructure;

import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.Station;
import com.ddareungi.station.domain.StationSearchPort;
import com.ddareungi.station.domain.StationBounds;
import com.ddareungi.station.domain.StationNameSearch;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Profile("local")
@Repository
public class InMemoryStationSearchAdapter implements StationSearchPort {
    private final List<Seed> seeds = List.of(
            new Seed("ST-001", "강남역 2번 출구", "서울특별시 강남구 강남대로", 37.4979, 127.0276, 20, 7),
            new Seed("ST-002", "역삼역 4번 출구", "서울특별시 강남구 테헤란로", 37.5006, 127.0364, 15, 2),
            new Seed("ST-003", "신논현역 6번 출구", "서울특별시 강남구 봉은사로", 37.5045, 127.0248, 25, 11)
    );

    @Override
    public List<Station> findNearby(Location center, int radiusMeters) {
        return seeds.stream()
                .map(seed -> seed.toStation(center))
                .filter(station -> station.distanceMeters() <= radiusMeters)
                .sorted(Comparator.comparingDouble(Station::distanceMeters))
                .toList();
    }

    @Override
    public List<Station> findInBounds(StationBounds bounds) {
        return seeds.stream().map(seed -> seed.toStation(bounds.center()))
                .filter(station -> bounds.contains(station.location())).toList();
    }

    @Override
    public List<Station> findByIds(List<String> ids, Location center) {
        return seeds.stream().filter(seed -> ids.contains(seed.id())).map(seed -> seed.toStation(center)).toList();
    }

    @Override
    public List<Station> searchByName(String query, Location center) {
        return StationNameSearch.select(seeds.stream().map(seed -> seed.toStation(center)).toList(), query, center);
    }

    private record Seed(String id, String name, String address, double latitude, double longitude, int racks, int bikes) {
        Station toStation(Location center) {
            Location location = new Location(latitude, longitude);
            return new Station(id, name, address, location, racks, bikes, distance(center, location), Instant.now());
        }

        private static double distance(Location a, Location b) {
            double earthRadius = 6_371_000;
            double lat = Math.toRadians(b.latitude() - a.latitude());
            double lon = Math.toRadians(b.longitude() - a.longitude());
            double value = Math.sin(lat / 2) * Math.sin(lat / 2)
                    + Math.cos(Math.toRadians(a.latitude())) * Math.cos(Math.toRadians(b.latitude()))
                    * Math.sin(lon / 2) * Math.sin(lon / 2);
            return earthRadius * 2 * Math.atan2(Math.sqrt(value), Math.sqrt(1 - value));
        }
    }
}
