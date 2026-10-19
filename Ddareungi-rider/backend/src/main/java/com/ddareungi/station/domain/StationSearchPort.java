package com.ddareungi.station.domain;

import java.util.List;

public interface StationSearchPort {
    List<Station> findNearby(Location center, int radiusMeters);
    List<Station> findByIds(List<String> ids, Location center);
    List<Station> searchByName(String query, Location center);
    List<Station> findInBounds(StationBounds bounds);
}
