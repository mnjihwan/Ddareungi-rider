package com.ddareungi.place;

import com.ddareungi.global.exception.ApiException;
import com.ddareungi.global.exception.ErrorCode;
import com.ddareungi.place.application.PlaceSearchCache;
import com.ddareungi.place.application.PlaceSearchService;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.Station;
import com.ddareungi.station.domain.StationSearchPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Controller + {@link PlaceSearchService} against a mocked {@link PlaceSearchProvider}: validation, merging,
 * de-duplication, station completion and error mapping. HTTP details of Kakao are covered by
 * {@code KakaoPlaceSearchProviderTest}.
 */
class PlaceSearchControllerTest {
    static final String SUBWAY = "SW8";
    PlaceSearchProvider provider;
    StationSearchPort stations;
    PlaceSearchController controller;

    @BeforeEach void setup() {
        provider = mock(PlaceSearchProvider.class);
        stations = mock(StationSearchPort.class);
        when(provider.configured()).thenReturn(true);
        when(provider.keyword(anyString(), any(), any())).thenReturn(List.of());
        when(provider.address(anyString())).thenReturn(List.of());
        controller = new PlaceSearchController(new PlaceSearchService(provider, stations));
    }

    @Test void reverseKeepsProviderPlaceAndAllowsNoAddress() {
        var chosen = new Location(37.5, 127.0);
        when(provider.reverse(chosen)).thenReturn(new Place("테스트빌딩", "서울 테스트로 10", chosen, null, ""));
        var place = controller.reverse(37.5, 127.0).data();
        assertThat(place.name()).isEqualTo("테스트빌딩");
        assertThat(place.address()).isEqualTo("서울 테스트로 10");
        assertThat(place.location().latitude()).isEqualTo(37.5);
        assertThat(place.location().longitude()).isEqualTo(127.0);
        assertThat(controller.reverse(0, 0).data()).isNull();
    }

    @Test void retriesWrongKeyboardForGeneralPlacesWithoutDiscardingCorrectedResults() {
        when(provider.keyword(eq("스타벅스"), isNull(), isNull())).thenReturn(List.of(place("스타벅스 강남점", "CE7", null)));
        assertThat(controller.search("tmxkqjrtm", null, null).data()).extracting(PlaceResponse::name)
                .containsExactly("스타벅스 강남점");
        verify(provider).keyword("tmxkqjrtm", null, null);
        verify(provider).keyword("스타벅스", null, null);
        verify(provider).keyword("tmxkqjrtm", null, SUBWAY);
        verify(provider, never()).address(anyString());
    }

    @Test void preservesLiteralEnglishPlaceWithoutSpeculativeKeyboardRetry() {
        // "rka" can also be composed as 감. An actual English match must take priority.
        when(provider.keyword(eq("rka"), isNull(), isNull())).thenReturn(List.of(place("RKA Cafe", "CE7", null)));
        assertThat(controller.search("rka", null, null).data()).extracting(PlaceResponse::name)
                .containsExactly("RKA Cafe");
        verify(provider, never()).keyword(eq("감"), any(), any());
        verify(provider, times(2)).keyword(anyString(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"강남얙", "ㄱㄴㅇ"})
    void resolvesTypoAndInitialSubwaysThroughRealRentalNames(String query) {
        var rental = mock(Station.class);
        when(rental.stationName()).thenReturn("101. 강남역 2번 출구");
        when(stations.searchByName(eq(query), any())).thenReturn(List.of(rental));
        when(provider.keyword(eq("강남역"), isNull(), eq(SUBWAY))).thenReturn(List.of(place("강남역 2호선", SUBWAY, null)));
        assertThat(controller.search(query, null, null).data()).extracting(PlaceResponse::name)
                .containsExactly("강남역 2호선");
    }

    @Test void acceptsEmptySearchResultsAfterAddressFallback() {
        assertThat(controller.search("강남역", null, null).data()).isEmpty();
        verify(provider).address("강남역");
    }

    @Test void prioritizesActualSubwaysEvenWhenMissingFromGeneralResultsAndDeduplicatesPlaces() {
        when(provider.keyword(eq("서울대입"), isNull(), isNull())).thenReturn(List.of(
                place("서울대입구역 카페", "CE7", "https://place.map.kakao.com/1"),
                place("서울대입구역 2호선", SUBWAY, "https://place.map.kakao.com/2")));
        when(provider.keyword(eq("서울대입"), isNull(), eq(SUBWAY))).thenReturn(List.of(
                place("서울대입구역 2호선", SUBWAY, "https://place.map.kakao.com/2"),
                place("서울대입구역 신설역", SUBWAY, "https://place.map.kakao.com/3")));
        var places = controller.search("서울대입", null, null).data();
        assertThat(places).extracting(PlaceResponse::name)
                .containsExactly("서울대입구역 2호선", "서울대입구역 신설역", "서울대입구역 카페");
        assertThat(places.getFirst().categoryGroupCode()).isEqualTo(SUBWAY);
        verify(provider, never()).address(anyString());
    }

    @Test void preservesGeneralResultsWhenSupplementarySubwayLookupFails() {
        when(provider.keyword(eq("서울대입"), isNull(), isNull())).thenReturn(List.of(place("서울대입구역 2호선", SUBWAY, null)));
        when(provider.keyword(eq("서울대입"), isNull(), eq(SUBWAY))).thenThrow(new PlaceProviderException("429", false));
        assertThat(controller.search("서울대입", null, null).data()).extracting(PlaceResponse::name)
                .containsExactly("서울대입구역 2호선");
    }

    @Test void keepsAddressFallbackAfterBothKeywordSearchesAreEmpty() {
        when(provider.address("테헤란로 212")).thenReturn(List.of(
                new Place("서울 강남구 테헤란로 212", "서울 강남구 테헤란로 212", new Location(37.5, 127.04), null, "")));
        assertThat(controller.search("테헤란로 212", null, null).data()).extracting(PlaceResponse::name)
                .containsExactly("서울 강남구 테헤란로 212");
    }

    @Test void passesSearchCenterToBothKeywordLookups() {
        controller.search("강남역", 37.5, 127.02);
        verify(provider).keyword("강남역", new Location(37.5, 127.02), null);
        verify(provider).keyword("강남역", new Location(37.5, 127.02), SUBWAY);
    }

    @Test void primaryProviderFailureIsSanitizedGatewayError() {
        when(provider.keyword(eq("강남역"), isNull(), isNull())).thenThrow(new PlaceProviderException("test-secret", false));
        assertThatThrownBy(() -> controller.search("강남역", null, null)).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(error.errorCode()).isEqualTo(ErrorCode.PLACE_PROVIDER_ERROR);
            assertThat(error.getReason()).doesNotContain("test-secret");
            assertThat(error.getCause()).isNull();
        });
    }

    @Test void providerTimeoutIsGatewayTimeout() {
        when(provider.keyword(eq("강남역"), isNull(), isNull())).thenThrow(new PlaceProviderException("slow", true));
        when(provider.reverse(any())).thenThrow(new PlaceProviderException("slow", true));
        when(provider.category(any(), any(), anyInt())).thenThrow(new PlaceProviderException("slow", true));
        for (var call : List.<Runnable>of(() -> controller.search("강남역", null, null),
                () -> controller.reverse(37.5, 127.0), () -> controller.nearbySubways(37.5, 127.0))) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
                assertThat(error.errorCode()).isEqualTo(ErrorCode.EXTERNAL_API_TIMEOUT);
                assertThat(error.getCause()).isNull();
            });
        }
    }

    @Test void validatesQueryBeforeCallingProvider() {
        assertThatThrownBy(() -> controller.search(null, null, null)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.PLACE_QUERY_REQUIRED));
        assertThatThrownBy(() -> controller.search(" ", null, null)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.PLACE_QUERY_REQUIRED));
        assertThatThrownBy(() -> controller.search("x", null, null)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER));
        assertThatThrownBy(() -> controller.search("x".repeat(101), null, null)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER));
        assertThatThrownBy(() -> controller.search("강남역", 37.5, null)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER));
        assertThatThrownBy(() -> controller.search("강남역", 91.0, 127.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.nearbySubways(Double.NaN, 127.02)).isInstanceOf(IllegalArgumentException.class);
        verify(provider, never()).keyword(any(), any(), any());
        verify(provider, never()).category(any(), any(), anyInt());
    }

    @Test void reportsMissingConfigurationAsUnavailable() {
        when(provider.configured()).thenReturn(false);
        assertThatThrownBy(() -> controller.search("강남역", null, null)).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(error.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
        });
        assertThatThrownBy(() -> controller.reverse(37.5, 127.0)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> controller.nearbySubways(37.5, 127.0)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        verify(provider, never()).keyword(any(), any(), any());
        verify(provider, never()).reverse(any());
        verify(provider, never()).category(any(), any(), anyInt());
    }

    @Test void completesPartialSubwayNamesFromRentalDataAndIgnoresAddressOnlySubwayMatches() {
        var rental = mock(Station.class);
        when(rental.stationName()).thenReturn("2111. 서울대입구역 1번출구");
        var unnamed = mock(Station.class);
        when(stations.searchByName(eq("서울대입"), any())).thenReturn(List.of(unnamed, rental));
        when(provider.keyword(eq("서울대입"), isNull(), isNull())).thenReturn(List.of(place("서울대입구 카페", "CE7", null)));
        when(provider.keyword(eq("서울대입"), isNull(), eq(SUBWAY))).thenReturn(List.of(place("관악산역 신림선", SUBWAY, null)));
        when(provider.keyword(eq("서울대입구역"), isNull(), eq(SUBWAY))).thenReturn(List.of(place("서울대입구역 2호선", SUBWAY, null)));
        assertThat(controller.search("서울대입", null, null).data()).extracting(PlaceResponse::name)
                .containsExactly("서울대입구역 2호선", "서울대입구 카페");
    }

    @Test void suggestsNearbySubwaysByDistanceWithoutDuplicateLines() {
        var center = new Location(37.5, 127.02);
        when(provider.category(SUBWAY, center, 2000)).thenReturn(List.of(
                new Place("가역 2호선", "", new Location(37.5001, 127.0201), null, SUBWAY),
                new Place("가역 신분당선", "", new Location(37.5002, 127.0202), null, SUBWAY),
                new Place("나역 9호선", "", new Location(37.501, 127.021), null, SUBWAY),
                new Place("먼역", "", new Location(38, 128), null, SUBWAY)));
        assertThat(controller.nearbySubways(37.5, 127.02).data()).extracting(PlaceResponse::name)
                .containsExactly("가역 2호선", "나역 9호선");
    }

    @Test void nearbySubwayProviderErrorIsSanitized() {
        when(provider.category(any(), any(), anyInt())).thenThrow(new PlaceProviderException("test-secret", false));
        assertThatThrownBy(() -> controller.nearbySubways(37.5, 127.02)).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(error.errorCode()).isEqualTo(ErrorCode.PLACE_PROVIDER_ERROR);
            assertThat(error.getReason()).doesNotContain("test-secret");
            assertThat(error.getCause()).isNull();
        });
    }

    @Test void generalAndSubwayLookupsRunInParallelWithRequestContext() throws Exception {
        // Each lookup waits until the other one has started: a sequential implementation would time out.
        var bothStarted = new java.util.concurrent.CountDownLatch(2);
        var subwayRequestId = new java.util.concurrent.atomic.AtomicReference<String>();
        when(provider.keyword(eq("강남역"), isNull(), isNull())).thenAnswer(invocation -> {
            bothStarted.countDown();
            assertThat(bothStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return List.of(place("강남역 카페", "CE7", null));
        });
        when(provider.keyword(eq("강남역"), isNull(), eq(SUBWAY))).thenAnswer(invocation -> {
            subwayRequestId.set(org.slf4j.MDC.get("requestId"));
            bothStarted.countDown();
            assertThat(bothStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return List.of(place("강남역 2호선", SUBWAY, null));
        });
        org.slf4j.MDC.put("requestId", "parallel-test");
        try {
            assertThat(controller.search("강남역", null, null).data()).extracting(PlaceResponse::name)
                    .containsExactly("강남역 2호선", "강남역 카페");
        } finally {
            org.slf4j.MDC.remove("requestId");
        }
        assertThat(subwayRequestId).hasValue("parallel-test");
    }

    @Test void primaryFailureCancelsTheParallelSubwayLookup() throws Exception {
        var subwayInterrupted = new java.util.concurrent.CountDownLatch(1);
        when(provider.keyword(eq("강남역"), isNull(), eq(SUBWAY))).thenAnswer(invocation -> {
            try { Thread.sleep(10_000); } catch (InterruptedException interrupted) { subwayInterrupted.countDown(); }
            return List.of();
        });
        when(provider.keyword(eq("강남역"), isNull(), isNull())).thenThrow(new PlaceProviderException("down", false));
        assertThatThrownBy(() -> controller.search("강남역", null, null)).isInstanceOf(ApiException.class);
        assertThat(subwayInterrupted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }

    @Test void cachesSuccessfulProviderAnswersPerQueryRoundedCenterAndCategory() {
        var cache = new MapCache();
        var cached = new PlaceSearchController(new PlaceSearchService(provider, stations, cache));
        when(provider.keyword(eq("강남역"), any(), isNull())).thenReturn(List.of(place("강남역 카페", "CE7", null)));
        cached.search("강남역", 37.49791, 127.02761);
        // 4th decimal differs only: same ~100 m cell, so both lookups are served from the cache.
        cached.search("강남역", 37.49794, 127.02764);
        verify(provider, times(1)).keyword(eq("강남역"), any(), isNull());
        verify(provider, times(1)).keyword(eq("강남역"), any(), eq(SUBWAY));
        assertThat(cache.entries).hasSize(2).allSatisfy((key, value) -> assertThat(key).startsWith("place:v1:"));
    }

    @Test void providerFailuresAreNotCachedAndCacheErrorsFailOpen() {
        var broken = new PlaceSearchCache() {
            @Override public List<Place> get(String key) { throw new IllegalStateException("redis down"); }
            @Override public void put(String key, List<Place> places) { throw new IllegalStateException("redis down"); }
        };
        var cached = new PlaceSearchController(new PlaceSearchService(provider, stations, broken));
        when(provider.keyword(eq("강남역"), isNull(), isNull())).thenReturn(List.of(place("강남역 카페", "CE7", null)));
        assertThat(cached.search("강남역", null, null).data()).extracting(PlaceResponse::name).containsExactly("강남역 카페");

        var cache = new MapCache();
        var withCache = new PlaceSearchController(new PlaceSearchService(provider, stations, cache));
        when(provider.category(any(), any(), anyInt())).thenThrow(new PlaceProviderException("down", false));
        assertThatThrownBy(() -> withCache.nearbySubways(37.5, 127.02)).isInstanceOf(ApiException.class);
        assertThat(cache.entries).isEmpty();
    }

    @Test void reverseGeocodingIsNeverCached() {
        var cache = new MapCache();
        var cached = new PlaceSearchController(new PlaceSearchService(provider, stations, cache));
        cached.reverse(37.5, 127.0);
        cached.reverse(37.5, 127.0);
        verify(provider, times(2)).reverse(new Location(37.5, 127.0));
        assertThat(cache.entries).isEmpty();
    }

    static class MapCache implements PlaceSearchCache {
        final java.util.Map<String, List<Place>> entries = new java.util.concurrent.ConcurrentHashMap<>();
        @Override public List<Place> get(String key) { return entries.get(key); }
        @Override public void put(String key, List<Place> places) { entries.put(key, places); }
    }

    private static Place place(String name, String category, String url) {
        return new Place(name, "", new Location(37.5, 127.02), url, category);
    }
}
