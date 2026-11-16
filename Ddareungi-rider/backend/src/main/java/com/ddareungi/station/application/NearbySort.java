package com.ddareungi.station.application;

import com.ddareungi.global.exception.ApiException;
import com.ddareungi.global.exception.ErrorCode;
import com.ddareungi.global.response.ApiErrorResponse.Detail;

import java.util.List;

/** {@code sort} of {@code GET /stations/nearby} (spec 11.1). Ties are always broken by stationId ascending. */
public enum NearbySort {
    /** Great-circle distance ascending. */
    DISTANCE("distance"),
    /** Rentable bikes descending (null last), then distance ascending. */
    PARKING_BIKE_TOT_CNT("parkingBikeTotCnt"),
    /** NFC-normalised name by Unicode code point ascending. */
    NAME("name");

    /** Regex accepted by the controller's {@code @Pattern}; keep in sync with the parameter values. */
    public static final String PARAMETER_PATTERN = "distance|parkingBikeTotCnt|name";

    private final String parameter;

    NearbySort(String parameter) { this.parameter = parameter; }

    public String parameter() { return parameter; }

    public static NearbySort fromParameter(String value) {
        for (NearbySort sort : values()) {
            if (sort.parameter.equals(value)) return sort;
        }
        throw new ApiException(ErrorCode.INVALID_PARAMETER, null,
                List.of(new Detail("sort", "distance, parkingBikeTotCnt, name 중 하나여야 합니다.")));
    }
}
