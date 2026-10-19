package com.ddareungi.station.domain;

public record Location(double latitude, double longitude) {
    public Location {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) throw new IllegalArgumentException("좌표는 유한한 숫자여야 합니다.");
        if (latitude < -90 || latitude > 90) throw new IllegalArgumentException("latitude 범위가 올바르지 않습니다.");
        if (longitude < -180 || longitude > 180) throw new IllegalArgumentException("longitude 범위가 올바르지 않습니다.");
    }

    public double distanceTo(Location other) {
        double lat = Math.toRadians(other.latitude - latitude);
        double lon = Math.toRadians(other.longitude - longitude);
        double value = Math.sin(lat / 2) * Math.sin(lat / 2)
                + Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(other.latitude))
                * Math.sin(lon / 2) * Math.sin(lon / 2);
        value = Math.max(0, Math.min(1, value));
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(value), Math.sqrt(1 - value));
    }
}
