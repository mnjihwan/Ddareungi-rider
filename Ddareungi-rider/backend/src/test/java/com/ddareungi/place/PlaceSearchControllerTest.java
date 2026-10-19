package com.ddareungi.place;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import com.ddareungi.station.domain.StationSearchPort;
import com.ddareungi.station.domain.Station;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PlaceSearchControllerTest {
    MockRestServiceServer server;
    PlaceSearchController controller;
    StationSearchPort stations;

    @BeforeEach void setup() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        stations = mock(StationSearchPort.class);
        controller = new PlaceSearchController(builder, JsonMapper.builder().build(), "test-secret", stations);
    }

    @Test void reverseAddressKeepsChosenCoordinatesAndUsesBuildingName() {
        server.expect(requestTo("https://dapi.kakao.com/v2/local/geo/coord2address.json?x=127.0&y=37.5&input_coord=WGS84"))
            .andRespond(withSuccess("""
            {"documents":[{"road_address":{"address_name":"서울 테스트로 10","building_name":"테스트빌딩"}}]}
            """,MediaType.APPLICATION_JSON));
        var place=controller.reverse(37.5,127.0).data();
        assertThat(place.name()).isEqualTo("테스트빌딩");
        assertThat(place.address()).isEqualTo("서울 테스트로 10");
        assertThat(place.location().latitude()).isEqualTo(37.5);
    }
    @Test void reverseAddressFallsBackToLandLotAndAllowsNoAddress() {
        server.expect(anything()).andRespond(withSuccess("""
            {"documents":[{"road_address":null,"address":{"address_name":"서울 테스트동 10"}}]}
            """,MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess("{\"documents\":[]}",MediaType.APPLICATION_JSON));
        assertThat(controller.reverse(37.5,127.0).data().name()).isEqualTo("서울 테스트동 10");
        assertThat(controller.reverse(0,0).data()).isNull();
    }

    @Test void retriesWrongKeyboardForGeneralPlacesWithoutDiscardingCorrectedResults() {
        server.expect(anything()).andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));
        server.expect(request -> assertThat(java.net.URLDecoder.decode(request.getURI().getRawQuery(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("query=스타벅스"))
                .andRespond(withSuccess("""
                {"documents":[{"place_name":"스타벅스 강남점","category_group_code":"CE7","x":"127.02","y":"37.5"}]}
                """, MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));
        assertThat(controller.search("tmxkqjrtm", null, null).data()).extracting(PlaceSearchController.Place::name)
                .containsExactly("스타벅스 강남점");
        server.verify();
    }

    @Test void preservesLiteralEnglishPlaceWithoutSpeculativeKeyboardRetry() {
        // "rka" can also be composed as 감. An actual English match must take priority.
        server.expect(anything()).andRespond(withSuccess("""
                {"documents":[{"place_name":"RKA Cafe","category_group_code":"CE7","x":"127.02","y":"37.5"}]}
                """, MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));
        assertThat(controller.search("rka", null, null).data()).extracting(PlaceSearchController.Place::name)
                .containsExactly("RKA Cafe");
        server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"강남얙", "ㄱㄴㅇ"})
    void resolvesTypoAndInitialSubwaysThroughRealRentalNames(String query) {
        var rental = mock(Station.class);
        when(rental.stationName()).thenReturn("101. 강남역 2번 출구");
        when(stations.searchByName(eq(query), any())).thenReturn(java.util.List.of(rental));
        server.expect(anything()).andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));
        server.expect(request -> assertThat(java.net.URLDecoder.decode(request.getURI().getRawQuery(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("query=강남역"))
                .andRespond(withSuccess("""
                {"documents":[{"place_name":"강남역 2호선","category_group_code":"SW8","x":"127.02","y":"37.5"}]}
                """, MediaType.APPLICATION_JSON));
        assertThat(controller.search(query, null, null).data()).extracting(PlaceSearchController.Place::name)
                .containsExactly("강남역 2호선");
        server.verify();
    }

    @Test void usesKakaoAuthenticationAndEncodesReservedCharacters() {
        server.expect(requestTo("https://dapi.kakao.com/v2/local/search/keyword.json?query=A%26B%20%23%2B&size=15&x=127.02&y=37.5&sort=distance"))
                .andExpect(header("Authorization", "KakaoAK test-secret"))
                .andRespond(withSuccess("""
                    {"documents":[{"place_name":"강남역","road_address_name":"서울 강남대로","address_name":"서울",
                    "x":"127.0283493","y":"37.4964196","place_url":"http://place.map.kakao.com/123"}]}
                    """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://dapi.kakao.com/v2/local/search/keyword.json?query=A%26B%20%23%2B&size=15&x=127.02&y=37.5&sort=distance&category_group_code=SW8"))
                .andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));
        var places = controller.search("A&B #+", 37.5, 127.02).data();
        assertThat(places).hasSize(1);
        assertThat(places.getFirst().name()).isEqualTo("강남역");
        assertThat(places.getFirst().address()).isEqualTo("서울 강남대로");
        assertThat(places.getFirst().location().latitude()).isCloseTo(37.4964196, within(0.0000001));
        assertThat(places.getFirst().location().longitude()).isCloseTo(127.0283493, within(0.0000001));
        assertThat(places.getFirst().sourceUrl()).isEqualTo("https://place.map.kakao.com/123");
        server.verify();
    }

    @Test void acceptsEmptySearchResults() {
        server.expect(anything()).andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://dapi.kakao.com/v2/local/search/address.json?query=%EA%B0%95%EB%82%A8%EC%97%AD&size=5"))
                .andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));
        assertThat(controller.search("강남역", null, null).data()).isEmpty();
    }

    @Test void prioritizesActualSubwaysEvenWhenMissingFromGeneralResultsAndDeduplicatesPlaces() {
        server.expect(anything()).andRespond(withSuccess("""
                {"documents":[
                {"place_name":"서울대입구역 카페","category_group_code":"CE7","x":"126.952","y":"37.48","place_url":"http://place.map.kakao.com/1"},
                {"place_name":"서울대입구역 2호선","category_group_code":"SW8","x":"126.953","y":"37.481","place_url":"http://place.map.kakao.com/2"}]}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://dapi.kakao.com/v2/local/search/keyword.json?query=%EC%84%9C%EC%9A%B8%EB%8C%80%EC%9E%85&size=15&category_group_code=SW8"))
                .andRespond(withSuccess("""
                {"documents":[
                {"place_name":"서울대입구역 2호선","category_group_code":"SW8","x":"126.953","y":"37.481","place_url":"http://place.map.kakao.com/2"},
                {"place_name":"서울대입구역 신설역","category_group_code":"SW8","x":"126.954","y":"37.482","place_url":"http://place.map.kakao.com/3"}]}
                """, MediaType.APPLICATION_JSON));
        var places = controller.search("서울대입", null, null).data();
        assertThat(places).extracting(PlaceSearchController.Place::name)
                .containsExactly("서울대입구역 2호선", "서울대입구역 신설역", "서울대입구역 카페");
        assertThat(places.getFirst().categoryGroupCode()).isEqualTo("SW8");
        server.verify();
    }

    @Test void preservesGeneralResultsWhenSupplementarySubwayLookupFails() {
        server.expect(anything()).andRespond(withSuccess("""
                {"documents":[{"place_name":"서울대입구역 2호선","category_group_code":"SW8","x":"126.953","y":"37.481"}]}
                """, MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("test-secret"));
        assertThat(controller.search("서울대입", null, null).data()).extracting(PlaceSearchController.Place::name)
                .containsExactly("서울대입구역 2호선");
        server.verify();
    }

    @Test void keepsAddressFallbackAfterBothKeywordSearchesAreEmpty() {
        server.expect(anything()).andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess("""
                {"documents":[{"address_name":"서울 강남구 테헤란로 212","x":"127.04","y":"37.5"}]}
                """, MediaType.APPLICATION_JSON));
        assertThat(controller.search("테헤란로 212", null, null).data()).extracting(PlaceSearchController.Place::name)
                .containsExactly("서울 강남구 테헤란로 212");
        server.verify();
    }

    @Test void rejectsProviderErrorsEvenWithHttp200() {
        server.expect(anything()).andRespond(withSuccess("{\"errorCode\":\"024\",\"errorMessage\":\"test-secret\"}", MediaType.APPLICATION_JSON));
        assertSafeGatewayError();
    }

    @Test void doesNotExposeCredentialsOrProviderErrorBodies() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("test-secret"));
        assertSafeGatewayError();
    }

    @Test void rejectsInvalidCoordinates() {
        server.expect(anything()).andRespond(withSuccess("{\"documents\":[{\"x\":\"NaN\",\"y\":\"37.4964196\"}]}", MediaType.APPLICATION_JSON));
        assertSafeGatewayError();
    }

    @Test void validatesQueryBeforeCallingProvider() {
        assertThatThrownBy(() -> controller.search(" ", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.search("x".repeat(101), null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.search("강남역", 37.5, null)).isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test void reportsMissingConfigurationAsUnavailable() {
        var missing = new PlaceSearchController(RestClient.builder(), JsonMapper.builder().build(), "", stations);
        assertThatThrownBy(() -> missing.search("강남역", null, null)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test void completesPartialSubwayNamesFromRentalDataAndIgnoresAddressOnlySubwayMatches() {
        var rental = mock(Station.class);
        when(rental.stationName()).thenReturn("2111. 서울대입구역 1번출구");
        var unnamed = mock(Station.class);
        when(stations.searchByName(eq("서울대입"), any())).thenReturn(java.util.List.of(unnamed, rental));
        server.expect(anything()).andRespond(withSuccess("""
                {"documents":[{"place_name":"서울대입구 카페","category_group_code":"CE7","x":"126.953","y":"37.481"}]}
                """, MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess("""
                {"documents":[{"place_name":"관악산역 신림선","category_group_code":"SW8","x":"126.95","y":"37.47"}]}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://dapi.kakao.com/v2/local/search/keyword.json?query=%EC%84%9C%EC%9A%B8%EB%8C%80%EC%9E%85%EA%B5%AC%EC%97%AD&size=15&category_group_code=SW8"))
                .andRespond(withSuccess("""
                {"documents":[{"place_name":"서울대입구역 2호선","category_group_code":"SW8","x":"126.953","y":"37.481"}]}
                """, MediaType.APPLICATION_JSON));
        assertThat(controller.search("서울대입", null, null).data()).extracting(PlaceSearchController.Place::name)
                .containsExactly("서울대입구역 2호선", "서울대입구 카페");
        server.verify();
    }

    private void assertSafeGatewayError() {
        assertThatThrownBy(() -> controller.search("강남역", null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(error.getReason()).doesNotContain("test-secret", "test-id");
                    assertThat(error.getCause()).isNull();
                });
    }

    @Test void suggestsNearbySubwaysByDistanceWithoutDuplicateLinesOrOtherCategories() {
        server.expect(requestTo("https://dapi.kakao.com/v2/local/search/category.json?category_group_code=SW8&x=127.02&y=37.5&radius=2000&sort=distance&size=15"))
                .andExpect(header("Authorization","KakaoAK test-secret"))
                .andRespond(withSuccess("""
                    {"documents":[
                    {"place_name":"가역 2호선","category_group_code":"SW8","x":"127.0201","y":"37.5001"},
                    {"place_name":"가역 신분당선","category_group_code":"SW8","x":"127.0202","y":"37.5002"},
                    {"place_name":"나역 9호선","category_group_code":"SW8","x":"127.021","y":"37.501"},
                    {"place_name":"음식점","category_group_code":"FD6","x":"127.02","y":"37.5"},
                    {"place_name":"먼역","category_group_code":"SW8","x":"128","y":"38"}]}
                    """,MediaType.APPLICATION_JSON));
        assertThat(controller.nearbySubways(37.5,127.02).data()).extracting(PlaceSearchController.Place::name)
                .containsExactly("가역 2호선","나역 9호선");
        server.verify();
    }

    @Test void rejectsInvalidSubwayCoordinatesAndSanitizesProviderErrors() {
        assertThatThrownBy(()->controller.nearbySubways(Double.NaN,127.02)).isInstanceOf(IllegalArgumentException.class);
        server.expect(anything()).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("test-secret"));
        assertThatThrownBy(()->controller.nearbySubways(37.5,127.02)).isInstanceOfSatisfying(ResponseStatusException.class,error->{
            assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(error.getReason()).doesNotContain("test-secret");
            assertThat(error.getCause()).isNull();
        });
        server.verify();
    }
}
