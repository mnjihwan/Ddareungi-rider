package com.ddareungi.place.infrastructure;

import com.ddareungi.place.Place;
import com.ddareungi.place.PlaceProviderException;
import com.ddareungi.station.domain.Location;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** Kakao Local HTTP contract: URLs, authentication, encoding, parsing and sanitized failures. */
class KakaoPlaceSearchProviderTest {
    MockRestServiceServer server;
    KakaoPlaceSearchProvider provider;

    @BeforeEach void setup() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new KakaoPlaceSearchProvider(builder, JsonMapper.builder().build(), "test-secret");
    }

    @Test void reverseAddressKeepsChosenCoordinatesAndUsesBuildingName() {
        server.expect(requestTo("https://dapi.kakao.com/v2/local/geo/coord2address.json?x=127.0&y=37.5&input_coord=WGS84"))
                .andExpect(header("Authorization", "KakaoAK test-secret"))
                .andRespond(withSuccess("""
                {"documents":[{"road_address":{"address_name":"서울 테스트로 10","building_name":"테스트빌딩"}}]}
                """, MediaType.APPLICATION_JSON));
        var place = provider.reverse(new Location(37.5, 127.0));
        assertThat(place.name()).isEqualTo("테스트빌딩");
        assertThat(place.address()).isEqualTo("서울 테스트로 10");
        assertThat(place.location()).isEqualTo(new Location(37.5, 127.0));
        server.verify();
    }

    @Test void reverseAddressFallsBackToLandLotAndAllowsNoAddress() {
        server.expect(anything()).andRespond(withSuccess("""
                {"documents":[{"road_address":null,"address":{"address_name":"서울 테스트동 10"}}]}
                """, MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));
        assertThat(provider.reverse(new Location(37.5, 127.0)).name()).isEqualTo("서울 테스트동 10");
        assertThat(provider.reverse(new Location(0, 0))).isNull();
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
                .andExpect(header("Authorization", "KakaoAK test-secret"))
                .andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));
        var center = new Location(37.5, 127.02);
        var places = provider.keyword("A&B #+", center, null);
        assertThat(places).hasSize(1);
        Place first = places.getFirst();
        assertThat(first.name()).isEqualTo("강남역");
        assertThat(first.address()).isEqualTo("서울 강남대로");
        assertThat(first.location().latitude()).isCloseTo(37.4964196, within(0.0000001));
        assertThat(first.location().longitude()).isCloseTo(127.0283493, within(0.0000001));
        assertThat(first.sourceUrl()).isEqualTo("https://place.map.kakao.com/123");
        assertThat(provider.keyword("A&B #+", center, "SW8")).isEmpty();
        server.verify();
    }

    @Test void categoryKeywordSearchKeepsOnlyThatCategoryAndRejectsForeignUrls() {
        server.expect(requestTo("https://dapi.kakao.com/v2/local/search/keyword.json?query=%EC%84%9C%EC%9A%B8%EB%8C%80%EC%9E%85&size=15&category_group_code=SW8"))
                .andRespond(withSuccess("""
                {"documents":[
                {"place_name":"서울대입구역 2호선","category_group_code":"SW8","x":"126.953","y":"37.481","place_url":"https://evil.example/1"},
                {"place_name":"서울대입구 카페","category_group_code":"CE7","x":"126.953","y":"37.481"}]}
                """, MediaType.APPLICATION_JSON));
        var places = provider.keyword("서울대입", null, "SW8");
        assertThat(places).extracting(Place::name).containsExactly("서울대입구역 2호선");
        assertThat(places.getFirst().sourceUrl()).isNull();
        server.verify();
    }

    @Test void addressSearchUsesAddressAsName() {
        server.expect(requestTo("https://dapi.kakao.com/v2/local/search/address.json?query=%EA%B0%95%EB%82%A8%EC%97%AD&size=5"))
                .andExpect(header("Authorization", "KakaoAK test-secret"))
                .andRespond(withSuccess("""
                {"documents":[{"address_name":"서울 강남구 테헤란로 212","x":"127.04","y":"37.5"}]}
                """, MediaType.APPLICATION_JSON));
        assertThat(provider.address("강남역")).singleElement().satisfies(place -> {
            assertThat(place.name()).isEqualTo("서울 강남구 테헤란로 212");
            assertThat(place.address()).isEqualTo("서울 강남구 테헤란로 212");
            assertThat(place.location()).isEqualTo(new Location(37.5, 127.04));
        });
        server.verify();
    }

    @Test void categorySearchRequestsSubwaysAroundCenterAndDropsOtherCategories() {
        server.expect(requestTo("https://dapi.kakao.com/v2/local/search/category.json?category_group_code=SW8&x=127.02&y=37.5&radius=2000&sort=distance&size=15"))
                .andExpect(header("Authorization", "KakaoAK test-secret"))
                .andRespond(withSuccess("""
                    {"documents":[
                    {"place_name":"가역 2호선","category_group_code":"SW8","x":"127.0201","y":"37.5001"},
                    {"place_name":"음식점","category_group_code":"FD6","x":"127.02","y":"37.5"}]}
                    """, MediaType.APPLICATION_JSON));
        assertThat(provider.category("SW8", new Location(37.5, 127.02), 2000)).extracting(Place::name)
                .containsExactly("가역 2호선");
        server.verify();
    }

    @Test void rejectsProviderErrorsEvenWithHttp200() {
        server.expect(anything()).andRespond(withSuccess("{\"errorCode\":\"024\",\"errorMessage\":\"test-secret\"}", MediaType.APPLICATION_JSON));
        assertSanitizedFailure(() -> provider.keyword("강남역", null, null));
    }

    @Test void doesNotExposeCredentialsOrProviderErrorBodies() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("test-secret"));
        server.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("test-secret"));
        assertSanitizedFailure(() -> provider.keyword("강남역", null, null));
        assertSanitizedFailure(() -> provider.category("SW8", new Location(37.5, 127.02), 2000));
        server.verify();
    }

    @Test void rejectsInvalidCoordinates() {
        server.expect(anything()).andRespond(withSuccess("{\"documents\":[{\"x\":\"NaN\",\"y\":\"37.4964196\"}]}", MediaType.APPLICATION_JSON));
        assertSanitizedFailure(() -> provider.keyword("강남역", null, null));
    }

    @Test void missingKeyMakesNoExternalRequest() {
        var missing = new KakaoPlaceSearchProvider(RestClient.builder(), JsonMapper.builder().build(), " ");
        assertThat(missing.configured()).isFalse();
        assertThatThrownBy(() -> missing.keyword("강남역", null, null)).isInstanceOf(PlaceProviderException.class);
        server.verify();
    }

    private static void assertSanitizedFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PlaceProviderException.class, error -> {
            assertThat(error.timeout()).isFalse();
            assertThat(error.getMessage()).doesNotContain("test-secret");
            assertThat(error.getCause()).isNull();
        });
    }
}
