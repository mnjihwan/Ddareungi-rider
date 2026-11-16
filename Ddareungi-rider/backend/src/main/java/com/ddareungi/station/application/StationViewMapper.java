package com.ddareungi.station.application;

import com.ddareungi.global.response.WarningCode;
import com.ddareungi.realtime.domain.RealtimeFreshness;
import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import com.ddareungi.station.domain.Station;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Classifies stations with {@link RealtimeFreshnessPolicy} against one instant per request and applies the public
 * list rules (spec 11.0, D1): UNAVAILABLE items are removed and reported via {@code meta.partial}; STALE items are
 * kept with their last counts and also make the list partial. Stations excluded by the publication gate never
 * reach this mapper (the port omits them) and are not counted as partial: they are not candidates.
 */
@Component
public class StationViewMapper {
    private final RealtimeFreshnessPolicy policy;

    public StationViewMapper(RealtimeFreshnessPolicy policy) {
        this.policy = policy;
    }

    public Instant now() { return policy.now(); }

    public StationView toView(Station station, Instant now) {
        var assessment = policy.classify(station.updatedAt(), now);
        return new StationView(station, assessment.status(), assessment.ageSeconds());
    }

    /** One evaluation instant for the whole list. */
    public StationListView toListView(List<Station> stations) {
        Instant now = policy.now();
        List<StationView> items = new ArrayList<>(stations.size());
        boolean stale = false;
        boolean dropped = false;
        for (Station station : stations) {
            StationView view = toView(station, now);
            if (view.realtimeStatus() == RealtimeFreshness.UNAVAILABLE) {
                dropped = true;
                continue;
            }
            stale |= view.realtimeStatus() == RealtimeFreshness.STALE;
            items.add(view);
        }
        List<WarningCode> warnings = new ArrayList<>(2);
        if (stale) warnings.add(WarningCode.STALE_DATA);
        if (dropped) warnings.add(WarningCode.REALTIME_DATA_UNAVAILABLE);
        return new StationListView(items, stale || dropped, warnings);
    }
}
