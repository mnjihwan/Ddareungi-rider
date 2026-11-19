package com.ddareungi.global.exception;

import org.springframework.http.HttpStatus;

/**
 * Public error codes (spec 10) with their HTTP status. The last three are extension codes kept outside the spec
 * table (phase 1 decision): {@code RENTAL_STOCK_CHANGED}, {@code METHOD_NOT_ALLOWED}, {@code RESOURCE_NOT_FOUND}.
 */
public enum ErrorCode {
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    INVALID_COORDINATE(HttpStatus.BAD_REQUEST, "유효하지 않은 좌표입니다."),
    OUT_OF_SERVICE_AREA(HttpStatus.UNPROCESSABLE_ENTITY, "서비스 대상 지역이 아닙니다."),
    INVALID_RADIUS(HttpStatus.BAD_REQUEST, "검색 반경이 올바르지 않습니다."),
    INVALID_LIMIT(HttpStatus.BAD_REQUEST, "조회 개수가 올바르지 않습니다."),
    PLACE_QUERY_REQUIRED(HttpStatus.BAD_REQUEST, "검색어를 입력해 주세요."),
    STATION_NOT_FOUND(HttpStatus.NOT_FOUND, "대여소를 찾지 못했습니다."),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "JSON 응답을 허용하지 않는 요청입니다."),
    ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, "사용 가능한 경로를 찾지 못했습니다."),
    NO_AVAILABLE_ORIGIN_STATION(HttpStatus.UNPROCESSABLE_ENTITY, "출발지 주변에 대여 가능한 대여소가 없습니다."),
    NO_AVAILABLE_RETURN_STATION(HttpStatus.UNPROCESSABLE_ENTITY, "목적지 주변에 반납할 대여소가 없습니다."),
    PREDICTION_NOT_AVAILABLE(HttpStatus.NOT_FOUND, "예측 데이터가 없습니다."),
    PREDICTION_OUT_OF_RANGE(HttpStatus.UNPROCESSABLE_ENTITY, "지원하는 예측 범위가 아닙니다."),
    REALTIME_DATA_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "실시간 정보를 확인하지 못했습니다."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "서비스를 일시적으로 사용할 수 없습니다."),
    PLACE_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "장소 정보를 가져오지 못했습니다."),
    WALK_ROUTE_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "도보 경로를 가져오지 못했습니다."),
    BIKE_ROUTE_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "자전거 경로를 가져오지 못했습니다."),
    PREDICTION_SERVICE_ERROR(HttpStatus.BAD_GATEWAY, "예측 서비스 응답을 가져오지 못했습니다."),
    EXTERNAL_API_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "외부 서비스 응답이 지연되고 있습니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type입니다."),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "요청 본문이 너무 큽니다."),
    UNSUPPORTED_ROUTE_OPTION(HttpStatus.UNPROCESSABLE_ENTITY, "지원하지 않는 경로 옵션입니다."),
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
    MAINTENANCE_MODE(HttpStatus.SERVICE_UNAVAILABLE, "서비스 점검 중입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),
    // Extension codes (not in spec 10).
    RENTAL_STOCK_CHANGED(HttpStatus.CONFLICT, "대여소 수량이 바뀌었습니다. 다시 검색해 주세요."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾지 못했습니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() { return status; }
    public String defaultMessage() { return defaultMessage; }
}
