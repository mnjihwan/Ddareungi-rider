package com.ddareungi.station.infrastructure;

import com.ddareungi.station.domain.Location;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Immutable uniform grid over WGS84 coordinates (cells of {@value #CELL_DEGREES}°, about 1.1 km north-south and
 * 0.9 km east-west in Seoul). Queries return exactly what a full scan with the same predicate returns, in the
 * original item order: the grid only narrows the candidates, and every candidate is checked with
 * {@link Location#distanceTo} / an inclusive box test. Near the poles, across the antimeridian, or when a query
 * would visit more cells than there are items, it falls back to the full scan.
 */
final class StationGridIndex<T> {
    static final double CELL_DEGREES = 0.01;
    private static final double EARTH_RADIUS_METERS = 6_371_000;
    /** Slack added to the angular bounds so rounding can never drop an item that the exact check would keep. */
    private static final double BOUND_SLACK_DEGREES = 1e-6;

    private final List<T> items;
    private final Location[] locations;
    private final Map<Long, int[]> cells;

    StationGridIndex(List<T> items, Function<T, Location> location) {
        this.items = List.copyOf(items);
        this.locations = new Location[this.items.size()];
        Map<Long, List<Integer>> building = new HashMap<>();
        for (int i = 0; i < this.items.size(); i++) {
            locations[i] = location.apply(this.items.get(i));
            building.computeIfAbsent(key(cell(locations[i].latitude()), cell(locations[i].longitude())), k -> new ArrayList<>()).add(i);
        }
        Map<Long, int[]> frozen = new HashMap<>(building.size() * 2);
        building.forEach((key, indexes) -> frozen.put(key, indexes.stream().mapToInt(Integer::intValue).toArray()));
        this.cells = frozen;
    }

    int size() { return items.size(); }

    /** Items whose location is within {@code radiusMeters} (inclusive) of {@code center}, in item order. */
    List<T> withinRadius(Location center, double radiusMeters) {
        if (radiusMeters < 0 || items.isEmpty()) return List.of();
        double angular = radiusMeters / EARTH_RADIUS_METERS;
        double latDelta = Math.toDegrees(angular) + BOUND_SLACK_DEGREES;
        double south = center.latitude() - latDelta, north = center.latitude() + latDelta;
        if (angular >= Math.PI / 2 || north >= 90 || south <= -90) return scanRadius(center, radiusMeters);
        // Largest longitude difference reachable within the angular radius (pole not inside the circle).
        double ratio = Math.sin(angular) / Math.cos(Math.toRadians(center.latitude()));
        if (ratio >= 1) return scanRadius(center, radiusMeters);
        double lonDelta = Math.toDegrees(Math.asin(ratio)) * (1 + 1e-9) + BOUND_SLACK_DEGREES;
        double west = center.longitude() - lonDelta, east = center.longitude() + lonDelta;
        if (west < -180 || east > 180) return scanRadius(center, radiusMeters);
        int[] candidates = candidates(south, north, west, east);
        if (candidates == null) return scanRadius(center, radiusMeters);
        List<T> result = new ArrayList<>();
        for (int index : candidates) if (center.distanceTo(locations[index]) <= radiusMeters) result.add(items.get(index));
        return result;
    }

    /** Items inside the inclusive box, in item order. */
    List<T> inBox(double south, double north, double west, double east) {
        if (items.isEmpty() || south > north || west > east) return List.of();
        int[] candidates = candidates(Math.max(-90, south), Math.min(90, north), Math.max(-180, west), Math.min(180, east));
        List<T> result = new ArrayList<>();
        if (candidates == null) {
            for (int i = 0; i < items.size(); i++) if (inside(locations[i], south, north, west, east)) result.add(items.get(i));
            return result;
        }
        for (int index : candidates) if (inside(locations[index], south, north, west, east)) result.add(items.get(index));
        return result;
    }

    /** Sorted item indexes of the cells covering the box, or null when a full scan is cheaper. */
    private int[] candidates(double south, double north, double west, double east) {
        long latFrom = cell(south), latTo = cell(north), lonFrom = cell(west), lonTo = cell(east);
        long cellCount = (latTo - latFrom + 1) * (lonTo - lonFrom + 1);
        if (cellCount > Math.max(cells.size(), items.size())) return null;
        int[] buffer = new int[16];
        int count = 0;
        for (long lat = latFrom; lat <= latTo; lat++) {
            for (long lon = lonFrom; lon <= lonTo; lon++) {
                int[] members = cells.get(key(lat, lon));
                if (members == null) continue;
                if (count + members.length > buffer.length) buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, count + members.length));
                System.arraycopy(members, 0, buffer, count, members.length);
                count += members.length;
            }
        }
        int[] result = Arrays.copyOf(buffer, count);
        Arrays.sort(result); // original item order, identical to a full scan
        return result;
    }

    private List<T> scanRadius(Location center, double radiusMeters) {
        List<T> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) if (center.distanceTo(locations[i]) <= radiusMeters) result.add(items.get(i));
        return result;
    }

    private static boolean inside(Location location, double south, double north, double west, double east) {
        return location.latitude() >= south && location.latitude() <= north
                && location.longitude() >= west && location.longitude() <= east;
    }

    private static long cell(double degrees) { return (long) Math.floor(degrees / CELL_DEGREES); }

    private static long key(long lat, long lon) { return (lat << 32) ^ (lon & 0xffffffffL); }
}
