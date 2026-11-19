package com.ddareungi.station.infrastructure;

import com.ddareungi.global.exception.ApiException;
import com.ddareungi.global.exception.ErrorCode;
import com.ddareungi.realtime.application.CachedRealtimeSnapshot;
import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Review H2: an empty or sharply shrunk catalog reload counts as a failure and keeps the previous catalog. */
class StationCatalogHolderTest {
    private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
    private final AtomicReference<List<StationCatalog.Row>> rows = new AtomicReference<>(List.of());
    private final StationCatalogHolder holder = new StationCatalogHolder(rows::get, Clock.fixed(NOW, ZoneOffset.UTC));

    private static List<StationCatalog.Row> rows(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new StationCatalog.Row("ST-" + i, "대여소 " + i, "서울", 37.5 + i * 0.0001, 127.0, 10))
                .toList();
    }

    @Test
    void firstLoadWithZeroRowsIsNotALoad() {
        rows.set(List.of());
        assertThat(holder.reload()).isFalse();
        assertThat(holder.loaded()).isFalse();
        assertThatThrownBy(holder::require).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));

        rows.set(rows(3)); // the next (retried) load with data succeeds
        assertThat(holder.reload()).isTrue();
        assertThat(holder.require().size()).isEqualTo(3);
    }

    @Test
    void zeroRowReloadKeepsThePreviousCatalog() {
        rows.set(rows(3)); // below the shrink-rule minimum, but an empty result is still rejected
        assertThat(holder.reload()).isTrue();
        StationCatalog previous = holder.current();

        rows.set(List.of());
        assertThat(holder.reload()).isFalse();
        assertThat(holder.current()).isSameAs(previous);
    }

    @Test
    void reloadShrinkingBelowHalfOfAtLeastTenRowsKeepsThePreviousCatalog() {
        rows.set(rows(100));
        assertThat(holder.reload()).isTrue();
        StationCatalog previous = holder.current();

        rows.set(rows(49)); // < 50%
        assertThat(holder.reload()).isFalse();
        assertThat(holder.current()).isSameAs(previous);

        rows.set(rows(50)); // exactly 50% is accepted
        assertThat(holder.reload()).isTrue();
        assertThat(holder.require().size()).isEqualTo(50);
    }

    @Test
    void shrinkRuleNeedsAtLeastTenPreviousRows() {
        rows.set(rows(9));
        assertThat(holder.reload()).isTrue();
        rows.set(rows(1)); // ~11%, but the previous catalog is too small for the ratio rule
        assertThat(holder.reload()).isTrue();
        assertThat(holder.require().size()).isEqualTo(1);

        rows.set(rows(10));
        assertThat(holder.reload()).isTrue();
        rows.set(rows(4)); // 40% of 10 rows
        assertThat(holder.reload()).isFalse();
        assertThat(holder.require().size()).isEqualTo(10);
    }

    @Test
    void normalGrowthAndSmallShrinkAreAccepted() {
        rows.set(rows(100));
        assertThat(holder.reload()).isTrue();
        rows.set(rows(250));
        assertThat(holder.reload()).isTrue();
        assertThat(holder.require().size()).isEqualTo(250);
        rows.set(rows(200));
        assertThat(holder.reload()).isTrue();
        assertThat(holder.require().size()).isEqualTo(200);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void healthIsDownBeforeLoadAfterAnEmptyFirstLoadAndForACatalogWithoutPublicStations() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var adapter = new JdbcStationSearchAdapter(jdbc, now -> CachedRealtimeSnapshot.empty(),
                RealtimeFreshnessPolicy.defaults(Clock.fixed(NOW, ZoneOffset.UTC)));
        var health = new StationCatalogHealthIndicator(adapter);
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);

        when(jdbc.query(eq(JdbcStationSearchAdapter.CATALOG_SQL), any(RowMapper.class))).thenReturn((List) List.of());
        assertThat(adapter.reloadCatalog()).isFalse();
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);

        // Rows exist but none is publishable (no name): nothing to serve, so not ready.
        when(jdbc.query(eq(JdbcStationSearchAdapter.CATALOG_SQL), any(RowMapper.class)))
                .thenReturn((List) List.of(new StationCatalog.Row("ST-1", null, "서울", 37.5, 127.0, 10)));
        assertThat(adapter.reloadCatalog()).isTrue();
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.health().getDetails()).containsEntry("empty", true);

        when(jdbc.query(eq(JdbcStationSearchAdapter.CATALOG_SQL), any(RowMapper.class))).thenReturn((List) rows(2));
        assertThat(adapter.reloadCatalog()).isTrue();
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }
}
