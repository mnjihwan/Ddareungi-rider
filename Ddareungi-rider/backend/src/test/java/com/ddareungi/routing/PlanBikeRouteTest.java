package com.ddareungi.routing;

import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import com.ddareungi.station.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanBikeRouteTest {
    StationSearchPort stations = mock(StationSearchPort.class);
    WalkingRouteProvider walking = mock(WalkingRouteProvider.class);
    BicycleRouteProvider bicycle = mock(BicycleRouteProvider.class);
    Location origin = new Location(37.50,127.02), destination = new Location(37.52,127.04);
    Station d1 = station("d1",new Location(37.5001,127.02),3,Instant.now());
    Station d2 = station("d2",new Location(37.501,127.02),5,Instant.now());
    Station a1 = station("a1",new Location(37.5201,127.04),0,Instant.now());
    Station a2 = station("a2",new Location(37.521,127.04),20,Instant.now());
    PlanBikeRoute planner = new PlanBikeRoute(stations,walking,bicycle,true);
    @BeforeEach void setup() {
        when(walking.configured()).thenReturn(true);
        when(bicycle.configured()).thenReturn(true);
        when(walking.forRequest()).thenReturn(walking);
        when(bicycle.forRequest()).thenReturn(bicycle);
        when(walking.attribution()).thenReturn("경로 제공 · Kakao Maps");
        when(bicycle.attribution()).thenReturn("경로 제공 · Kakao Maps");
        when(stations.findNearby(origin,1000)).thenReturn(List.of(d1,d2));
        when(stations.findNearby(destination,1000)).thenReturn(List.of(a1,a2));
        when(stations.findByIds(anyList(),eq(origin))).thenAnswer(invocation -> {
            List<String> ids=invocation.getArgument(0); return List.of(ids.contains("d1")?d1:d2);
        });
        when(walking.durations(eq(List.of(origin)),anyList())).thenReturn(new Double[][]{{50.0,100.0}});
        when(walking.durations(anyList(),eq(List.of(destination)))).thenReturn(new Double[][]{{200.0},{20.0}});
        when(bicycle.durations(anyList(),anyList())).thenReturn(new Double[][]{{600.0,800.0},{500.0,400.0}});
        when(walking.route(any(),any())).thenAnswer(invocation->new RouteProvider.Leg("walk",150,60,
                List.of(invocation.getArgument(0),invocation.getArgument(1)),List.of()));
        when(bicycle.route(any(),any())).thenAnswer(invocation->new RouteProvider.Leg("bicycle",150,60,
                List.of(invocation.getArgument(0),invocation.getArgument(1)),List.of()));
    }
    @Test void comparesActualTravelTimesAndReturnsWalkingCyclingWalking() {
        var plan=planner.execute(origin,destination,null);
        assertThat(plan.departureStation().stationId()).isEqualTo("d2");
        assertThat(plan.arrivalStation().stationId()).isEqualTo("a2");
        assertThat(plan.legs()).extracting(RouteProvider.Leg::mode).containsExactly("walk","bicycle","walk");
        assertThat(plan.distanceMeters()).isEqualTo(450); assertThat(plan.durationSeconds()).isEqualTo(180);
        assertThat(plan.attribution()).isEqualTo("경로 제공 · Kakao Maps");
        // Three matrices in total: two walking (first/last mile) and one cycling.
        verify(walking,times(2)).durations(anyList(),anyList());
        verify(bicycle,times(1)).durations(anyList(),anyList());
        // Three legs in total: walk, ride, walk — each on its own provider.
        verify(walking).route(origin,d2.location());
        verify(bicycle).route(d2.location(),a2.location());
        verify(walking).route(a2.location(),destination);
        verify(walking,times(2)).route(any(),any());
        verify(bicycle,times(1)).route(any(),any());
    }
    @Test void differentSegmentProvidersAreBothCredited() {
        when(bicycle.attribution()).thenReturn("openrouteservice · © OpenStreetMap contributors");
        assertThat(planner.execute(origin,destination,null).attribution())
                .isEqualTo("경로 제공 · Kakao Maps / openrouteservice · © OpenStreetMap contributors");
    }
    @Test void excludesEmptyUnknownAndStaleDepartureButAcceptsZeroCountForReturning() {
        when(stations.findNearby(origin,1000)).thenReturn(List.of(
            station("empty",d1.location(),0,Instant.now()),station("unknown",d1.location(),null,Instant.now()),
            station("stale",d1.location(),10,Instant.now().minusSeconds(301)),d1));
        when(stations.findNearby(destination,1000)).thenReturn(List.of(a1));
        when(walking.durations(eq(List.of(origin)),anyList())).thenReturn(new Double[][]{{50.0}});
        when(walking.durations(anyList(),eq(List.of(destination)))).thenReturn(new Double[][]{{20.0}});
        when(bicycle.durations(anyList(),anyList())).thenReturn(new Double[][]{{400.0}});
        assertThat(planner.execute(origin,destination,null).arrivalStation().availableBikeCount()).isZero();
        verify(bicycle).durations(List.of(d1.location()),List.of(a1.location()));
    }
    @Test void stopsBeforeRoutingWhenNoRentableStation() {
        when(stations.findNearby(origin,1000)).thenReturn(List.of(station("empty",d1.location(),0,Instant.now())));
        assertCode("NO_RENTAL_BIKES");
        verify(walking,never()).durations(anyList(),anyList());
        verify(bicycle,never()).durations(anyList(),anyList());
    }
    @Test void rejectsUnreachablePairsInsteadOfInventingStraightLineRoutes() {
        when(bicycle.durations(anyList(),anyList())).thenReturn(new Double[][]{{null,null},{null,null}});
        assertCode("NO_CONNECTED_ROUTE");
        verify(walking,never()).route(any(),any());
        verify(bicycle,never()).route(any(),any());
    }
    @Test void rechecksStockAfterProviderCalls() {
        when(stations.findByIds(anyList(),eq(origin))).thenReturn(List.of(station("d2",d2.location(),0,Instant.now())));
        assertCode("RENTAL_STOCK_CHANGED");
    }
    @Test void doesNotRouteSampleModeOrMissingKey() {
        assertThat(new PlanBikeRoute(stations,walking,bicycle,false).configured()).isFalse();
        when(walking.configured()).thenReturn(false); assertCode("ROUTING_NOT_CONFIGURED");
        verifyNoInteractions(stations);
    }
    @Test void eitherSegmentProviderMissingMeansNotConfigured() {
        when(bicycle.configured()).thenReturn(false);
        assertThat(planner.configured()).isFalse();
        assertCode("ROUTING_NOT_CONFIGURED");
        verifyNoInteractions(stations);
    }
    @Test void segmentProviderErrorsPropagateWithTheirOwnCode() {
        when(bicycle.durations(anyList(),anyList())).thenThrow(
                new RouteException(HttpStatus.BAD_GATEWAY,"BIKE_ROUTE_PROVIDER_ERROR","경로를 가져오지 못했어요."));
        assertCode("BIKE_ROUTE_PROVIDER_ERROR");
        when(walking.durations(eq(List.of(origin)),anyList())).thenThrow(
                new RouteException(HttpStatus.BAD_GATEWAY,"WALK_ROUTE_PROVIDER_ERROR","경로를 가져오지 못했어요."));
        assertCode("WALK_ROUTE_PROVIDER_ERROR");
    }
    @Test void validatesEndpointsBeforeUsingProvider() {
        assertThatThrownBy(()->planner.execute(null,destination,null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(walking,bicycle,stations);
    }
    @Test void refusesUnavailablePinnedStationInsteadOfSilentlyChangingIt() {
        assertThatThrownBy(()->planner.execute(origin,destination,"missing"))
                .isInstanceOfSatisfying(RouteException.class,e->assertThat(e.code).isEqualTo("PINNED_STATION_NOT_FOUND"));
    }
    @Test void explicitlyPinnedStationIsResolvedByIdOutsideAutomaticSearchRadius() {
        var pinned = station("pinned",new Location(37.55,127.02),3,Instant.now());
        when(stations.findByIds(List.of("pinned"),origin)).thenReturn(List.of(pinned));
        var plan = planner.execute(origin,destination,"pinned");
        assertThat(plan.departureStation().stationId()).isEqualTo("pinned");
        verify(stations,never()).findNearby(origin,1000);
        verify(walking).route(origin,pinned.location());
    }
    @Test void staleNearbyInformationIsNotReportedAsMissingStations() {
        when(stations.findNearby(origin,1000)).thenReturn(List.of(station("old",d1.location(),5,Instant.now().minusSeconds(301))));
        assertCode("STATION_STATUS_UNAVAILABLE");
        verify(walking,never()).forRequest();
        verify(bicycle,never()).forRequest();
    }
    @Test void genuinelyMissingNearbyStationsAreReportedSeparately() {
        when(stations.findNearby(origin,1000)).thenReturn(List.of());
        assertCode("NO_RENTAL_STATION");
    }
    @Test void emptyPinnedStationDoesNotFallBackSilently() {
        when(stations.findByIds(List.of("d1"),origin)).thenReturn(List.of(station("d1",d1.location(),0,Instant.now())));
        assertThatThrownBy(()->planner.execute(origin,destination,"d1"))
                .isInstanceOfSatisfying(RouteException.class,e->assertThat(e.code).isEqualTo("PINNED_STATION_EMPTY"));
    }
    @Test void unknownPinnedStockHasAnAccurateError() {
        when(stations.findByIds(List.of("d1"),origin)).thenReturn(List.of(station("d1",d1.location(),null,null)));
        assertThatThrownBy(()->planner.execute(origin,destination,"d1"))
                .isInstanceOfSatisfying(RouteException.class,e->assertThat(e.code).isEqualTo("STATION_STATUS_UNAVAILABLE"));
    }
    @Test void distanceAloneDoesNotPreventProviderRouting() {
        var remote = new Location(38.1,127.04);
        assertThat(origin.distanceTo(remote)).isGreaterThan(50_000);
        when(stations.findNearby(remote,1000)).thenReturn(List.of(station("remote1",remote,0,Instant.now()),station("remote2",remote,0,Instant.now())));
        when(walking.durations(anyList(),eq(List.of(remote)))).thenReturn(new Double[][]{{200.0},{20.0}});
        assertThat(planner.execute(origin,remote,null).destination()).isEqualTo(remote);
        verify(walking).route(remote,remote);
    }
    @Test void explicitlyPinnedReturnStationAcceptsZeroBikes() {
        when(stations.findByIds(List.of("a1"),destination)).thenReturn(List.of(a1));
        var plan = planner.execute(origin,destination,null,"a1");
        assertThat(plan.arrivalStation().stationId()).isEqualTo("a1");
        assertThat(plan.arrivalStation().availableBikeCount()).isZero();
        verify(stations,never()).findNearby(destination,1000);
    }
    @Test void missingPinnedReturnStationDoesNotFallBack() {
        when(stations.findByIds(List.of("missing"),destination)).thenReturn(List.of());
        assertThatThrownBy(()->planner.execute(origin,destination,null,"missing"))
            .isInstanceOfSatisfying(RouteException.class,e->assertThat(e.code).isEqualTo("PINNED_STATION_NOT_FOUND"));
        verify(walking,never()).forRequest();
        verify(bicycle,never()).forRequest();
    }
    @Test void stalePinnedReturnStationIsRejected() {
        when(stations.findByIds(List.of("a1"),destination)).thenReturn(List.of(station("a1",a1.location(),0,Instant.now().minusSeconds(301))));
        assertThatThrownBy(()->planner.execute(origin,destination,null,"a1"))
            .isInstanceOfSatisfying(RouteException.class,e->assertThat(e.code).isEqualTo("STATION_STATUS_UNAVAILABLE"));
    }
    // ---- D2: route candidates use the shared FRESH rule (RealtimeFreshnessPolicy, 120 s / +30 s skew) ----
    static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
    PlanBikeRoute fixedPlanner() { return fixedPlanner(Clock.fixed(NOW, ZoneOffset.UTC)); }
    PlanBikeRoute fixedPlanner(Clock clock) {
        return new PlanBikeRoute(stations,walking,bicycle,true,Duration.ofSeconds(10),RealtimeFreshnessPolicy.defaults(clock));
    }
    /** Nearby departures: the candidate under test plus a FRESH reference d1; one arrival a1. */
    private void departureCandidates(Station candidate) {
        var reference = station("d1",d1.location(),3,NOW);
        when(stations.findNearby(origin,1000)).thenReturn(List.of(candidate,reference));
        when(stations.findNearby(destination,1000)).thenReturn(List.of(station("a1",a1.location(),0,NOW)));
        when(stations.findByIds(anyList(),eq(origin))).thenAnswer(invocation -> {
            List<String> ids=invocation.getArgument(0); return ids.contains(candidate.stationId())?List.of(candidate):List.of(reference);
        });
        when(walking.durations(eq(List.of(origin)),anyList())).thenAnswer(invocation -> {
            List<?> to=invocation.getArgument(1); return to.size()==2?new Double[][]{{10.0,500.0}}:new Double[][]{{500.0}};
        });
        when(walking.durations(anyList(),eq(List.of(destination)))).thenReturn(new Double[][]{{20.0}});
        when(bicycle.durations(anyList(),anyList())).thenAnswer(invocation -> {
            List<?> from=invocation.getArgument(0); return from.size()==2?new Double[][]{{100.0},{100.0}}:new Double[][]{{100.0}};
        });
    }
    private String departureFor(int ageSeconds) {
        var candidate = station("cand",new Location(37.50005,127.02),4,NOW.minusSeconds(ageSeconds));
        departureCandidates(candidate);
        return fixedPlanner().execute(origin,destination,null).departureStation().stationId();
    }
    @Test void departureAged119sIsFresh() { assertThat(departureFor(119)).isEqualTo("cand"); }
    @Test void departureAgedExactly120sIsStillFresh() { assertThat(departureFor(120)).isEqualTo("cand"); }
    @Test void departureAged121sIsStaleAndExcluded() {
        assertThat(departureFor(121)).isEqualTo("d1");
        verify(bicycle).durations(List.of(d1.location()),List.of(a1.location()));
    }
    @Test void fractionalAgeIsFlooredSo120point9sIsFresh() {
        var candidate = station("cand",new Location(37.50005,127.02),4,NOW.minusMillis(120_900));
        departureCandidates(candidate);
        assertThat(fixedPlanner().execute(origin,destination,null).departureStation().stationId()).isEqualTo("cand");
    }
    @Test void departureStampedUpTo30sInTheFutureIsFresh() { assertThat(departureFor(-30)).isEqualTo("cand"); }
    @Test void departureStampedMoreThan30sInTheFutureIsExcluded() { assertThat(departureFor(-31)).isEqualTo("d1"); }
    // Under the former 300 s rule a 200 s-old value was a route candidate; under D2 it is STALE and excluded.
    @Test void staleDepartureThatWasAcceptedUnderThe300sRuleIsNowExcluded() { assertThat(departureFor(200)).isEqualTo("d1"); }
    @Test void onlyStaleDeparturesWithinMaxStaleAreReportedAsStatusUnavailable() {
        when(stations.findNearby(origin,1000)).thenReturn(List.of(
                station("s1",d1.location(),5,NOW.minusSeconds(121)),station("s2",d2.location(),5,NOW.minusSeconds(600))));
        assertThatThrownBy(()->fixedPlanner().execute(origin,destination,null))
                .isInstanceOfSatisfying(RouteException.class,e->{
                    assertThat(e.code).isEqualTo("STATION_STATUS_UNAVAILABLE");
                    assertThat(e.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
        verify(walking,never()).forRequest();
    }
    @Test void pinnedDepartureStaleAt121sIsRejectedWith503() {
        when(stations.findByIds(List.of("p"),origin)).thenReturn(List.of(station("p",d1.location(),5,NOW.minusSeconds(121))));
        assertThatThrownBy(()->fixedPlanner().execute(origin,destination,"p"))
                .isInstanceOfSatisfying(RouteException.class,e->{
                    assertThat(e.code).isEqualTo("STATION_STATUS_UNAVAILABLE");
                    assertThat(e.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
        verify(walking,never()).forRequest();
    }
    @Test void pinnedDepartureAt120sIsAccepted() {
        var pinned = station("p",d1.location(),5,NOW.minusSeconds(120));
        when(stations.findByIds(List.of("p"),origin)).thenReturn(List.of(pinned));
        when(stations.findNearby(destination,1000)).thenReturn(List.of(station("a2",a2.location(),20,NOW)));
        when(walking.durations(eq(List.of(origin)),anyList())).thenReturn(new Double[][]{{50.0}});
        when(walking.durations(anyList(),eq(List.of(destination)))).thenReturn(new Double[][]{{20.0}});
        when(bicycle.durations(anyList(),anyList())).thenReturn(new Double[][]{{400.0}});
        assertThat(fixedPlanner().execute(origin,destination,"p").departureStation().stationId()).isEqualTo("p");
    }
    @Test void pinnedReturnStaleAt121sIsRejectedWith503() {
        when(stations.findByIds(List.of("a1"),destination)).thenReturn(List.of(station("a1",a1.location(),0,NOW.minusSeconds(121))));
        when(stations.findNearby(origin,1000)).thenReturn(List.of(station("d1",d1.location(),3,NOW)));
        assertThatThrownBy(()->fixedPlanner().execute(origin,destination,null,"a1"))
                .isInstanceOfSatisfying(RouteException.class,e->{
                    assertThat(e.code).isEqualTo("STATION_STATUS_UNAVAILABLE");
                    assertThat(e.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
    }
    @Test void staleReturnCandidateIsExcludedButZeroBikeFreshReturnIsKept() {
        when(stations.findNearby(origin,1000)).thenReturn(List.of(station("d1",d1.location(),3,NOW)));
        when(stations.findByIds(anyList(),eq(origin))).thenReturn(List.of(station("d1",d1.location(),3,NOW)));
        when(stations.findNearby(destination,1000)).thenReturn(List.of(
                station("staleReturn",a2.location(),20,NOW.minusSeconds(121)),station("a1",a1.location(),0,NOW.minusSeconds(120))));
        when(walking.durations(eq(List.of(origin)),anyList())).thenReturn(new Double[][]{{50.0}});
        when(walking.durations(anyList(),eq(List.of(destination)))).thenReturn(new Double[][]{{20.0}});
        when(bicycle.durations(anyList(),anyList())).thenReturn(new Double[][]{{400.0}});
        assertThat(fixedPlanner().execute(origin,destination,null).arrivalStation().stationId()).isEqualTo("a1");
        verify(bicycle).durations(List.of(d1.location()),List.of(a1.location()));
    }
    @Test void oneEvaluationInstantPerRequestIncludingTheStockRecheck() {
        // The clock advances 100 s on every read. With a single now per request a 119 s-old departure stays
        // FRESH through candidate selection and the post-routing recheck; a second read would make it STALE.
        var reads = new AtomicInteger();
        var advancing = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return NOW.plusSeconds(100L * reads.getAndIncrement()); }
        };
        var candidate = station("cand",new Location(37.50005,127.02),4,NOW.minusSeconds(119));
        departureCandidates(candidate);
        assertThat(fixedPlanner(advancing).execute(origin,destination,null).departureStation().stationId()).isEqualTo("cand");
        assertThat(reads.get()).isEqualTo(1);
    }
    @Test void freshnessBoundaryFollowsTheInjectedPolicy() {
        // realtime.freshness.fresh-seconds is externalised: a 300 s policy accepts a 200 s-old departure.
        var candidate = station("cand",new Location(37.50005,127.02),4,NOW.minusSeconds(200));
        departureCandidates(candidate);
        var lenient = new PlanBikeRoute(stations,walking,bicycle,true,Duration.ofSeconds(10),
                new RealtimeFreshnessPolicy(Clock.fixed(NOW,ZoneOffset.UTC),300,600));
        assertThat(lenient.execute(origin,destination,null).departureStation().stationId()).isEqualTo("cand");
    }
    private void assertCode(String code) { assertThatThrownBy(()->planner.execute(origin,destination,null))
            .isInstanceOfSatisfying(RouteException.class,e->assertThat(e.code).isEqualTo(code)); }
    private static Station station(String id,Location location,Integer stock,Instant time) {
        return new Station(id,id,"서울",location,10,stock,0,time);
    }
}
