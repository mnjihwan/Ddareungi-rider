package com.ddareungi.station.application;

import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.Station;
import com.ddareungi.station.domain.StationSearchPort;
import com.ddareungi.station.domain.StationBounds;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FindNearbyStationsUseCase {
    private final StationSearchPort stationSearchPort;

    public FindNearbyStationsUseCase(StationSearchPort stationSearchPort) {
        this.stationSearchPort = stationSearchPort;
    }

    public List<Station> execute(double latitude, double longitude, int radiusMeters) {
        return stationSearchPort.findNearby(new Location(latitude, longitude), radiusMeters);
    }

    public List<Station> findByIds(List<String> ids, double latitude, double longitude) {
        return stationSearchPort.findByIds(ids.stream().distinct().toList(), new Location(latitude, longitude));
    }

    public List<Station> findInBounds(double south, double north, double west, double east) {
        return stationSearchPort.findInBounds(new StationBounds(south, north, west, east));
    }

    public List<Station> searchByName(String query, double latitude, double longitude) {
        if (query.trim().length() < 2 || query.trim().length() > 100) throw new IllegalArgumentException("검색어는 2~100자로 입력해 주세요.");
        return stationSearchPort.searchByName(query.trim(), new Location(latitude, longitude));
    }
}
