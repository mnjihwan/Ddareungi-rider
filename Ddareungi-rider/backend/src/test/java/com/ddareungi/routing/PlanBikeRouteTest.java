package com.ddareungi.routing;

import com.ddareungi.station.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanBikeRouteTest {
    StationSearchPort stations = mock(StationSearchPort.class);
    RouteProvider provider = mock(RouteProvider.class);
    Location origin = new Location(37.50,127.02), destination = new Location(37.52,127.04);
    Station d1 = station("d1",new Location(37.5001,127.02),3,Instant.now());
    Station d2 = station("d2",new Location(37.501,127.02),5,Instant.now());
    Station a1 = station("a1",new Location(37.5201,127.04),0,Instant.now());
    Station a2 = station("a2",new Location(37.521,127.04),20,Instant.now());
    PlanBikeRoute planner = new PlanBikeRoute(stations,provider,true);
    @BeforeEach void setup() {
        when(provider.configured()).thenReturn(true);
        when(provider.forRequest()).thenReturn(provider);
        when(provider.attribution()).thenReturn("경로 제공 · Kakao Maps");
        when(stations.findNearby(origin,1000)).thenReturn(List.of(d1,d2));
        when(stations.findNearby(destination,1000)).thenReturn(List.of(a1,a2));
        when(stations.findByIds(anyList(),eq(origin))).thenAnswer(invocation -> {
            List<String> ids=invocation.getArgument(0); return List.of(ids.contains("d1")?d1:d2);
        });
        when(provider.durations(eq("foot-walking"),eq(List.of(origin)),anyList())).thenReturn(new Double[][]{{50.0,100.0}});
        when(provider.durations(eq("foot-walking"),anyList(),eq(List.of(destination)))).thenReturn(new Double[][]{{200.0},{20.0}});
        when(provider.durations(eq("cycling-regular"),anyList(),anyList())).thenReturn(new Double[][]{{600.0,800.0},{500.0,400.0}});
        when(provider.directions(anyString(),any(),any())).thenAnswer(invocation->new RouteProvider.Leg(
                invocation.getArgument(0).equals("foot-walking")?"walk":"bicycle",150,60,
                List.of(invocation.getArgument(1),invocation.getArgument(2)),List.of()));
    }
    @Test void comparesActualTravelTimesAndReturnsWalkingCyclingWalking() {
        var plan=planner.execute(origin,destination,null);
        assertThat(plan.departureStation().stationId()).isEqualTo("d2");
        assertThat(plan.arrivalStation().stationId()).isEqualTo("a2");
        assertThat(plan.legs()).extracting(RouteProvider.Leg::mode).containsExactly("walk","bicycle","walk");
        assertThat(plan.distanceMeters()).isEqualTo(450); assertThat(plan.durationSeconds()).isEqualTo(180);
        verify(provider,times(3)).durations(anyString(),anyList(),anyList());
        verify(provider,times(3)).directions(anyString(),any(),any());
    }
    @Test void excludesEmptyUnknownAndStaleDepartureButAcceptsZeroCountForReturning() {
        when(stations.findNearby(origin,1000)).thenReturn(List.of(
            station("empty",d1.location(),0,Instant.now()),station("unknown",d1.location(),null,Instant.now()),
            station("stale",d1.location(),10,Instant.now().minusSeconds(301)),d1));
        when(stations.findNearby(destination,1000)).thenReturn(List.of(a1));
        when(provider.durations(eq("foot-walking"),eq(List.of(origin)),anyList())).thenReturn(new Double[][]{{50.0}});
        when(provider.durations(eq("foot-walking"),anyList(),eq(List.of(destination)))).thenReturn(new Double[][]{{20.0}});
        when(provider.durations(eq("cycling-regular"),anyList(),anyList())).thenReturn(new Double[][]{{400.0}});
        assertThat(planner.execute(origin,destination,null).arrivalStation().availableBikeCount()).isZero();
        verify(provider).durations("cycling-regular",List.of(d1.location()),List.of(a1.location()));
    }
    @Test void stopsBeforeRoutingWhenNoRentableStation() {
        when(stations.findNearby(origin,1000)).thenReturn(List.of(station("empty",d1.location(),0,Instant.now())));
        assertCode("NO_RENTAL_BIKES"); verify(provider,never()).durations(anyString(),anyList(),anyList());
    }
    @Test void rejectsUnreachablePairsInsteadOfInventingStraightLineRoutes() {
        when(provider.durations(eq("cycling-regular"),anyList(),anyList())).thenReturn(new Double[][]{{null,null},{null,null}});
        assertCode("NO_CONNECTED_ROUTE"); verify(provider,never()).directions(anyString(),any(),any());
    }
    @Test void rechecksStockAfterProviderCalls() {
        when(stations.findByIds(anyList(),eq(origin))).thenReturn(List.of(station("d2",d2.location(),0,Instant.now())));
        assertCode("RENTAL_STOCK_CHANGED");
    }
    @Test void doesNotRouteSampleModeOrMissingKey() {
        assertThat(new PlanBikeRoute(stations,provider,false).configured()).isFalse();
        when(provider.configured()).thenReturn(false); assertCode("ROUTING_NOT_CONFIGURED");
        verifyNoInteractions(stations);
    }
    @Test void validatesEndpointsBeforeUsingProvider() {
        assertThatThrownBy(()->planner.execute(null,destination,null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(provider,stations);
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
        verify(provider).directions("foot-walking",origin,pinned.location());
    }
    @Test void staleNearbyInformationIsNotReportedAsMissingStations() {
        when(stations.findNearby(origin,1000)).thenReturn(List.of(station("old",d1.location(),5,Instant.now().minusSeconds(301))));
        assertCode("STATION_STATUS_UNAVAILABLE");
        verify(provider,never()).forRequest();
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
        when(provider.durations(eq("foot-walking"),anyList(),eq(List.of(remote)))).thenReturn(new Double[][]{{200.0},{20.0}});
        assertThat(planner.execute(origin,remote,null).destination()).isEqualTo(remote);
        verify(provider).directions("foot-walking",remote,remote);
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
        verify(provider,never()).forRequest();
    }
    @Test void stalePinnedReturnStationIsRejected() {
        when(stations.findByIds(List.of("a1"),destination)).thenReturn(List.of(station("a1",a1.location(),0,Instant.now().minusSeconds(301))));
        assertThatThrownBy(()->planner.execute(origin,destination,null,"a1"))
            .isInstanceOfSatisfying(RouteException.class,e->assertThat(e.code).isEqualTo("STATION_STATUS_UNAVAILABLE"));
    }
    private void assertCode(String code) { assertThatThrownBy(()->planner.execute(origin,destination,null))
            .isInstanceOfSatisfying(RouteException.class,e->assertThat(e.code).isEqualTo(code)); }
    private static Station station(String id,Location location,Integer stock,Instant time) {
        return new Station(id,id,"서울",location,10,stock,0,time);
    }
}
