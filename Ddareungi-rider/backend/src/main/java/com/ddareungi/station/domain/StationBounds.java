package com.ddareungi.station.domain;

public record StationBounds(double south, double north, double west, double east) {
    public StationBounds {
        new Location(south, west);
        new Location(north, east);
        if (south >= north || west >= east || north - south > 1 || east - west > 1)
            throw new IllegalArgumentException("지도 조회 영역이 올바르지 않습니다.");
    }

    public Location center() { return new Location((south + north) / 2, (west + east) / 2); }
    public boolean contains(Location location) {
        return location.latitude() >= south && location.latitude() <= north
                && location.longitude() >= west && location.longitude() <= east;
    }
}
