package com.ddareungi.place;

import com.ddareungi.global.exception.ApiException;
import com.ddareungi.global.exception.ErrorCode;
import com.ddareungi.global.response.ApiErrorResponse.Detail;
import com.ddareungi.global.response.ApiResponse;
import com.ddareungi.global.response.CoordinateResponse;
import com.ddareungi.place.application.PlaceSearchService;
import com.ddareungi.station.domain.Location;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/** Place endpoints: request validation and response mapping only; search logic lives in {@link PlaceSearchService}. */
@RestController
@RequestMapping("/api/v1/places")
public class PlaceSearchController {
    private final PlaceSearchService places;

    public PlaceSearchController(PlaceSearchService places) { this.places = places; }

    @GetMapping("/reverse")
    public ApiResponse<PlaceResponse> reverse(@RequestParam double latitude, @RequestParam double longitude) {
        var place = places.reverse(new Location(latitude, longitude));
        return ApiResponse.ok(place == null ? null : response(place));
    }

    @GetMapping("/search")
    public ApiResponse<List<PlaceResponse>> search(@RequestParam(required = false) String query,
            @RequestParam(required = false) Double latitude,
            @RequestParam(required = false) Double longitude) {
        if (query == null || query.isBlank()) throw new ApiException(ErrorCode.PLACE_QUERY_REQUIRED, null,
                List.of(new Detail("query", "검색어를 입력해 주세요.")));
        String term = query.trim();
        // Minimum length stays 2 (spec says 1); the difference is recorded in the spec (phase 1 decision).
        if (term.length() < 2 || term.length() > 100) throw new ApiException(ErrorCode.INVALID_PARAMETER,
                "검색어는 2~100자로 입력해 주세요.", List.of(new Detail("query", "2~100자여야 합니다.")));
        if ((latitude == null) != (longitude == null)) throw new ApiException(ErrorCode.INVALID_PARAMETER,
                "검색 중심의 위도와 경도를 함께 입력해 주세요.",
                List.of(new Detail(latitude == null ? "latitude" : "longitude", "위도와 경도를 함께 입력해야 합니다.")));
        @Nullable Location center = latitude == null ? null : new Location(latitude, longitude);
        return ApiResponse.ok(places.search(term, center).stream().map(PlaceSearchController::response).toList());
    }

    @GetMapping("/nearby-subways")
    public ApiResponse<List<PlaceResponse>> nearbySubways(@RequestParam double latitude, @RequestParam double longitude) {
        return ApiResponse.ok(places.nearbySubways(new Location(latitude, longitude)).stream()
                .map(PlaceSearchController::response).toList());
    }

    private static PlaceResponse response(Place place) {
        return new PlaceResponse(place.name(), place.address(), CoordinateResponse.from(place.location()),
                place.sourceUrl(), place.categoryGroupCode());
    }
}
