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
 * new catalog off the request path and swaps it in; a failed reload keeps the previous catalog. Until the first
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

    /** @return true when a new catalog was loaded; false keeps the previous one (or none). */
    boolean reload() {
        try {
            StationCatalog loaded = StationCatalog.build(loader.get(), clock.instant());
            StationCatalog previous = catalog;
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

    @Nullable StationCatalog current() { return catalog; }

    boolean loaded() { return catalog != null; }

    StationCatalog require() {
        StationCatalog current = catalog;
        if (current == null) throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "대여소 정보를 준비하고 있어요. 잠시 후 다시 시도해 주세요.");
        return current;
    }
}
