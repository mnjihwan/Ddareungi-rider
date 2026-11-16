package com.ddareungi.place;

import com.ddareungi.place.application.PlaceSearchService;
import com.ddareungi.place.infrastructure.KakaoPlaceSearchProvider;
import com.ddareungi.station.domain.StationSearchPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Controller → service → real Kakao provider over MockRestServiceServer. The general and subway lookups run in
 * parallel, so expectations are matched by URL in any order.
 */
class PlaceSearchEndToEndTest {
    MockRestServiceServer server;
    PlaceSearchController controller;

    @BeforeEach void setup() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        var provider = new KakaoPlaceSearchProvider(builder, JsonMapper.builder().build(), "test-secret");
        controller = new PlaceSearchController(new PlaceSearchService(provider, mock(StationSearchPort.class)));
    }

    @Test void searchCallsGeneralAndSubwayKeywordWithAuthenticationAndMapsResponse() {
        server.expect(requestTo("https://dapi.kakao.com/v2/local/search/keyword.json?query=A%26B%20%23%2B&size=15&x=127.02&y=37.5&sort=distance"))
                .andExpect(header("Authorization", "KakaoAK test-secret"))
                .andRespond(withSuccess("""
                    {"documents":[{"place_name":"강남역","road_address_name":"서울 강남대로","address_name":"서울",
                    "x":"127.0283493","y":"37.4964196","place_url":"http://place.map.kakao.com/123"}]}
                    """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://dapi.kakao.com/v2/local/search/keyword.json?query=A%26B%20%23%2B&size=15&x=127.02&y=37.5&sort=distance&category_group_code=SW8"))
                .andExpect(header("Authorization", "KakaoAK test-secret"))
                .andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));
        var places = controller.search("A&B #+", 37.5, 127.02).data();
        assertThat(places).singleElement().satisfies(place -> {
            assertThat(place.name()).isEqualTo("강남역");
            assertThat(place.address()).isEqualTo("서울 강남대로");
            assertThat(place.location().latitude()).isCloseTo(37.4964196, within(0.0000001));
            assertThat(place.sourceUrl()).isEqualTo("https://place.map.kakao.com/123");
        });
        server.verify();
    }

    @Test void emptyKeywordResultsFallBackToAddressSearch() {
        server.expect(requestTo("https://dapi.kakao.com/v2/local/search/keyword.json?query=%ED%85%8C%ED%97%A4%EB%9E%80%EB%A1%9C%20212&size=15"))
                .andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://dapi.kakao.com/v2/local/search/keyword.json?query=%ED%85%8C%ED%97%A4%EB%9E%80%EB%A1%9C%20212&size=15&category_group_code=SW8"))
                .andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://dapi.kakao.com/v2/local/search/address.json?query=%ED%85%8C%ED%97%A4%EB%9E%80%EB%A1%9C%20212&size=5"))
                .andRespond(withSuccess("""
                {"documents":[{"address_name":"서울 강남구 테헤란로 212","x":"127.04","y":"37.5"}]}
                """, MediaType.APPLICATION_JSON));
        assertThat(controller.search("테헤란로 212", null, null).data()).extracting(PlaceResponse::name)
                .containsExactly("서울 강남구 테헤란로 212");
        server.verify();
    }

    @Test void nearbySubwaysFiltersCategoryDistanceAndDuplicateLines() {
        server.expect(requestTo("https://dapi.kakao.com/v2/local/search/category.json?category_group_code=SW8&x=127.02&y=37.5&radius=2000&sort=distance&size=15"))
                .andExpect(header("Authorization", "KakaoAK test-secret"))
                .andRespond(withSuccess("""
                    {"documents":[
                    {"place_name":"가역 2호선","category_group_code":"SW8","x":"127.0201","y":"37.5001"},
                    {"place_name":"가역 신분당선","category_group_code":"SW8","x":"127.0202","y":"37.5002"},
                    {"place_name":"나역 9호선","category_group_code":"SW8","x":"127.021","y":"37.501"},
                    {"place_name":"음식점","category_group_code":"FD6","x":"127.02","y":"37.5"},
                    {"place_name":"먼역","category_group_code":"SW8","x":"128","y":"38"}]}
                    """, MediaType.APPLICATION_JSON));
        assertThat(controller.nearbySubways(37.5, 127.02).data()).extracting(PlaceResponse::name)
                .containsExactly("가역 2호선", "나역 9호선");
        server.verify();
    }
}
