package com.ddareungi.station.infrastructure;

import com.ddareungi.global.exception.ApiException;
import com.ddareungi.global.exception.ErrorCode;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;

/**
 * Holds the current {@link StationCatalog} behind a volatile reference (R3, D4). {@link #reload()} builds a complete
 * new catalog off the request path and swaps it in; a failed reload keeps the previous catalog, and so does an empty
 * or sharply shrunk result (see {@link #reload()}). Until the first
 * successful load, {@link #require()} throws {@code ApiException(SERVICE_UNAVAILABLE)} (503).
 */
final class StationCatalogHolder {
    private static final Logger log = LoggerFactory.getLogger(StationCatalogHolder.class);

    private final Supplier<List<StationCatalog.Row>> loader;
    private final Clock clock;
    private volatile @Nullable StationCatalog catalog;

    StationCatalogHolder(Supplier<List<StationCatalog.Row>> loader, Clock clock) {
        this.loader = loader;
        this.clock = clock;
    }

    /**
     * @return true when a new catalog was loaded; false keeps the previous one (or none). A suspicious result is
     *         treated like a failed load (review H2): zero rows, or fewer than half the previous rows when the
     *         previous catalog had at least {@value #SHRINK_RULE_MIN_PREVIOUS_ROWS} rows. A truncated or wrongly
     *         filtered query must not wipe the served catalog; the scheduler retries every
     *         {@code station.catalog.retry-interval} while reloads fail. A first load with zero rows does not count
     *         as loaded, so station APIs keep answering 503 and readiness stays DOWN.
     */
    boolean reload() {
        try {
            StationCatalog loaded = StationCatalog.build(loader.get(), clock.instant());
            StationCatalog previous = catalog;
            String rejection = rejectionReason(loaded, previous);
            if (rejection != null) {
                log.warn("Station catalog reload rejected ({}: {} rows); {}", rejection, loaded.size(),
                        previous == null ? "no catalog loaded yet, station APIs answer 503"
                                : "keeping the catalog loaded at " + previous.loadedAt() + " (" + previous.size() + " rows)");
                return false;
            }
            catalog = loaded;
            log.info("Station catalog loaded: {} rows, {} public (previous: {})", loaded.size(), loaded.publicEntries().size(),
                    previous == null ? "none" : previous.size() + " rows");
            return true;
        } catch (RuntimeException exception) {
            StationCatalog previous = catalog;
            log.warn("Station catalog reload failed ({}); {}", exception.getClass().getSimpleName(),
                    previous == null ? "no catalog loaded yet, station APIs answer 503" : "keeping the catalog loaded at " + previous.loadedAt());
            return false;
        }
    }

    /** Below this many previous rows the shrink ratio is not checked (small sample/test catalogs change a lot). */
    static final int SHRINK_RULE_MIN_PREVIOUS_ROWS = 10;

    static @Nullable String rejectionReason(StationCatalog loaded, @Nullable StationCatalog previous) {
        if (loaded.size() == 0) return "empty result";
        if (previous != null && previous.size() >= SHRINK_RULE_MIN_PREVIOUS_ROWS
                && (long) loaded.size() * 2 < previous.size()) {
            return "shrank below 50% of the previous " + previous.size() + " rows";
        }
        return null;
    }

    @Nullable StationCatalog current() { return catalog; }

    boolean loaded() { return catalog != null; }

    StationCatalog require() {
        StationCatalog current = catalog;
        if (current == null) throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "대여소 정보를 준비하고 있어요. 잠시 후 다시 시도해 주세요.");
        return current;
    }
}
