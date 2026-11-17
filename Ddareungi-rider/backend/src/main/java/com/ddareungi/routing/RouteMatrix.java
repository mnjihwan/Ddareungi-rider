package com.ddareungi.routing;

import org.jspecify.annotations.Nullable;
import java.util.function.UnaryOperator;

/**
 * Result of comparing candidate pairs (A6, phase 3 plan 2 change 3). Each cell is one of:
 * <ul>
 *   <li>routed: a provider route with its duration and distance,</li>
 *   <li>no route: the provider answered and confirmed there is no road route (never a zero-minute route),</li>
 *   <li>failed: the provider could not be asked (outage, quota, timeout, ...). A failure is recorded per cell so a
 *       search can still answer with the pairs that succeeded ({@code meta.partial}); it is never reported as
 *       "no route".</li>
 * </ul>
 */
public final class RouteMatrix {
    public record Cell(@Nullable Double durationSeconds, @Nullable Double distanceMeters,
                       @Nullable RouteException failure) {
        private static final Cell NO_ROUTE = new Cell(null, null, null);

        public static Cell route(double durationSeconds, double distanceMeters) {
            return new Cell(durationSeconds, distanceMeters, null);
        }
        public static Cell noRoute() { return NO_ROUTE; }
        public static Cell failed(RouteException failure) { return new Cell(null, null, failure); }

        public boolean routed() { return failure == null && durationSeconds != null; }
        public boolean failed() { return failure != null; }
        public boolean noRoad() { return failure == null && durationSeconds == null; }
    }

    private final Cell[][] cells;

    public RouteMatrix(Cell[][] cells) {
        this.cells = cells;
    }

    /** All cells routed or unreachable (null), with the given distances (same shape, required for routed cells). */
    public static RouteMatrix of(Double[][] durations, Double[][] distances) {
        var cells = new Cell[durations.length][];
        for (int i = 0; i < durations.length; i++) {
            cells[i] = new Cell[durations[i].length];
            for (int j = 0; j < durations[i].length; j++) {
                Double duration = durations[i][j];
                cells[i][j] = duration == null ? Cell.noRoute() : Cell.route(duration, distances[i][j]);
            }
        }
        return new RouteMatrix(cells);
    }

    public int rows() { return cells.length; }
    public int columns() { return cells.length == 0 ? 0 : cells[0].length; }
    public Cell cell(int row, int column) { return cells[row][column]; }

    /**
     * Durations with null for unreachable pairs. Kept for callers of the former all-or-nothing contract: the first
     * failed cell (row-major order) is thrown, as before.
     */
    public Double[][] durations() {
        var result = new Double[cells.length][];
        for (int i = 0; i < cells.length; i++) {
            result[i] = new Double[cells[i].length];
            for (int j = 0; j < cells[i].length; j++) {
                if (cells[i][j].failed()) throw cells[i][j].failure();
                result[i][j] = cells[i][j].durationSeconds();
            }
        }
        return result;
    }

    /** Copy with every recorded failure replaced (e.g. engine codes translated into segment provider errors). */
    public RouteMatrix mapFailures(UnaryOperator<RouteException> translate) {
        var mapped = new Cell[cells.length][];
        for (int i = 0; i < cells.length; i++) {
            mapped[i] = new Cell[cells[i].length];
            for (int j = 0; j < cells[i].length; j++) {
                var cell = cells[i][j];
                mapped[i][j] = cell.failed() ? Cell.failed(translate.apply(cell.failure())) : cell;
            }
        }
        return new RouteMatrix(mapped);
    }
}
