package com.ddareungi.station.application;

import com.ddareungi.realtime.domain.RealtimeFreshness;
import com.ddareungi.station.domain.StationCandidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Grid aggregation of station candidates for {@code /stations/clusters} (R4). Pure function, no I/O.
 *
 * <h2>Cell rule</h2>
 * The grid is anchored at (0°, 0°), so a station keeps its cell when the map is panned. With cell size {@code d}, a
 * coordinate {@code x} belongs to cell {@code k} when {@code k*d <= x < (k+1)*d} as evaluated in double arithmetic
 * ({@link #cellIndex}). Latitude and longitude use the same {@code d}.
 *
 * <h2>Item rule</h2>
 * Only FRESH and STALE candidates are aggregated; UNAVAILABLE candidates are left out and reported, like A3 lists.
 * Per non-empty cell: {@code latitude}/{@code longitude} = arithmetic mean of the member stations' (joined)
 * coordinates, {@code stationCount} = members, {@code parkingBikeTotCnt} = sum of the known bike counts of the
 * members (FRESH and STALE; an unknown count would be skipped, but the port never returns one for FRESH/STALE),
 * {@code freshCount} = FRESH members. Items are ordered by cell, south to north, then west to east.
 */
public final class StationClusters {
    private StationClusters() {}

    /** One non-empty cell. */
    public record Cluster(double latitude, double longitude, int stationCount, int parkingBikeTotCnt, int freshCount) {}

    /** Aggregation result with the inputs that the A3 partial rule needs. */
    public record Result(List<Cluster> clusters, boolean anyStale, boolean anyUnavailable) {
        public Result {
            clusters = List.copyOf(clusters);
        }

        /** Sum of {@code stationCount} = number of FRESH/STALE candidates aggregated. */
        public int stationTotal() {
            return clusters.stream().mapToInt(Cluster::stationCount).sum();
        }
    }

    /** Cell index of {@code degrees} for cell size {@code cellDegrees} (see the class comment). */
    public static long cellIndex(double degrees, double cellDegrees) {
        long index = (long) Math.floor(degrees / cellDegrees);
        // The division can round across an integer; one step of correction restores k*d <= x < (k+1)*d.
        if (degrees < index * cellDegrees) index--;
        else if (degrees >= (index + 1) * cellDegrees) index++;
        return index;
    }

    public static Result aggregate(List<StationCandidate> candidates, double cellDegrees) {
        if (!(cellDegrees > 0)) throw new IllegalStateException("cellDegrees must be positive: " + cellDegrees);
        Map<Cell, Accumulator> cells = new HashMap<>();
        boolean anyStale = false;
        boolean anyUnavailable = false;
        for (StationCandidate candidate : candidates) {
            RealtimeFreshness status = candidate.status();
            if (status == RealtimeFreshness.UNAVAILABLE) {
                anyUnavailable = true;
                continue;
            }
            anyStale |= status == RealtimeFreshness.STALE;
            var location = candidate.station().location();
            Cell cell = new Cell(cellIndex(location.latitude(), cellDegrees), cellIndex(location.longitude(), cellDegrees));
            cells.computeIfAbsent(cell, key -> new Accumulator()).add(candidate);
        }
        List<Map.Entry<Cell, Accumulator>> ordered = new ArrayList<>(cells.entrySet());
        ordered.sort(Map.Entry.comparingByKey(Comparator.comparingLong(Cell::lat).thenComparingLong(Cell::lon)));
        List<Cluster> clusters = ordered.stream().map(entry -> entry.getValue().cluster()).toList();
        return new Result(clusters, anyStale, anyUnavailable);
    }

    private record Cell(long lat, long lon) {}

    private static final class Accumulator {
        private double latitudeSum;
        private double longitudeSum;
        private int count;
        private int bikes;
        private int fresh;

        void add(StationCandidate candidate) {
            var station = candidate.station();
            latitudeSum += station.location().latitude();
            longitudeSum += station.location().longitude();
            count++;
            if (station.availableBikeCount() != null) bikes += station.availableBikeCount();
            if (candidate.status() == RealtimeFreshness.FRESH) fresh++;
        }

        Cluster cluster() {
            return new Cluster(latitudeSum / count, longitudeSum / count, count, bikes, fresh);
        }
    }
}
