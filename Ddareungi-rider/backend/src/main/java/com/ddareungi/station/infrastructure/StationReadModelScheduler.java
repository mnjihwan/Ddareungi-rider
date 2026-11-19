package com.ddareungi.station.infrastructure;

import com.ddareungi.realtime.infrastructure.RealtimeSnapshotCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background refresh of the in-memory read model (R3, D4/D5). On start it loads the catalog and polls the realtime
 * snapshot synchronously (failures are logged; the instance then reports not-ready through the
 * {@code stationCatalog} health indicator and station APIs answer 503 until a load succeeds). Afterwards the realtime
 * snapshot is polled every {@code realtime.snapshot.poll-interval} and the catalog is reloaded every
 * {@code station.catalog.reload-interval} (retried every {@code retry-interval} while none is loaded).
 * Both run on dedicated daemon threads, never on request threads.
 */
@Profile("infra")
@Component
public class StationReadModelScheduler implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(StationReadModelScheduler.class);

    private final JdbcStationSearchAdapter adapter;
    private final RealtimeSnapshotCache realtime;
    private final StationCatalogProperties catalogProperties;
    private volatile ScheduledExecutorService executor;
    private volatile long lastCatalogLoad = Long.MIN_VALUE;

    public StationReadModelScheduler(JdbcStationSearchAdapter adapter, RealtimeSnapshotCache realtime,
                                     StationCatalogProperties catalogProperties) {
        this.adapter = adapter;
        this.realtime = realtime;
        this.catalogProperties = catalogProperties;
    }

    @Override
    public void start() {
        refreshCatalog(true);
        pollRealtime();
        var threads = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "station-read-model");
            thread.setDaemon(true);
            return thread;
        });
        Duration poll = realtime.properties().pollInterval();
        Duration retry = catalogProperties.retryInterval().compareTo(catalogProperties.reloadInterval()) < 0
                ? catalogProperties.retryInterval() : catalogProperties.reloadInterval();
        threads.scheduleWithFixedDelay(this::pollRealtime, poll.toMillis(), poll.toMillis(), TimeUnit.MILLISECONDS);
        threads.scheduleWithFixedDelay(() -> refreshCatalog(false), retry.toMillis(), retry.toMillis(), TimeUnit.MILLISECONDS);
        executor = threads;
    }

    @Override
    public void stop() {
        ScheduledExecutorService threads = executor;
        executor = null;
        if (threads != null) threads.shutdownNow();
    }

    @Override
    public boolean isRunning() { return executor != null; }

    void pollRealtime() {
        try {
            realtime.poll(adapter.catalogStationIds());
        } catch (RuntimeException exception) { // poll handles storage errors itself; never kill the schedule
            log.warn("Realtime snapshot poll failed unexpectedly ({})", exception.getClass().getSimpleName());
        }
    }

    void refreshCatalog(boolean force) {
        try {
            long now = System.nanoTime();
            boolean due = force || !adapter.catalogLoaded()
                    || now - lastCatalogLoad >= catalogProperties.reloadInterval().toNanos();
            if (due && adapter.reloadCatalog()) lastCatalogLoad = now;
        } catch (RuntimeException exception) {
            log.warn("Station catalog refresh failed unexpectedly ({})", exception.getClass().getSimpleName());
        }
    }
}
