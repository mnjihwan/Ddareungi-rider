package com.ddareungi.global.exception;

import com.ddareungi.global.response.ApiErrorResponse.Detail;
import com.ddareungi.global.response.ApiResponse;
import com.ddareungi.global.web.RequestIdFilter;
import com.ddareungi.routing.RouteException;
import com.ddareungi.station.domain.Location;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Envelope and error-code contract of the handler, independent of the real controllers. */
class GlobalExceptionHandlerTest {
    static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");
    MockMvc mvc;

    @RestController
    static class ProbeController {
        static RuntimeException next;

        @GetMapping("/probe/ok") ApiResponse<Map<String, String>> ok() { return ApiResponse.ok(Map.of("value", "x")); }
        @GetMapping("/probe/throw") ApiResponse<Void> fail() { throw next; }
        @GetMapping("/probe/params") ApiResponse<Integer> params(@RequestParam int count) { return ApiResponse.ok(count); }
        @PostMapping("/probe/body") ApiResponse<Body> body(@Valid @RequestBody Body body) { return ApiResponse.ok(body); }
        @PostMapping("/probe/location") ApiResponse<Location> location(@RequestBody Location location) { return ApiResponse.ok(location); }
    }

    record Body(@DecimalMax("90") double latitude, @NotBlank String name) {}

    @BeforeEach void setup() {
        var clock = new DefaultListableBeanFactory();
        clock.registerSingleton("clock", Clock.fixed(NOW, ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler(clock.getBeanProvider(Clock.class)))
                .addFilters(new RequestIdFilter())
                .build();
    }

    private ResultActions throwing(RuntimeException exception) throws Exception {
        ProbeController.next = exception;
        return mvc.perform(get("/probe/throw"));
    }

    private static void assertFailureEnvelope(ResultActions result, HttpStatus status, String code) throws Exception {
        result.andExpect(status().is(status.value()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.message").isString())
                .andExpect(jsonPath("$.error.details").isArray())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.meta.requestId").isString())
                .andExpect(jsonPath("$.meta.partial").doesNotExist())
                .andExpect(jsonPath("$.meta.warnings").doesNotExist())
                .andExpect(jsonPath("$.timestamp").value("2026-11-13T00:00:00Z"));
    }

    @Test void successEnvelopeHasNoErrorAndCarriesRequestId() throws Exception {
        mvc.perform(get("/probe/ok").header(RequestIdFilter.HEADER, "client-trace_1"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER, "client-trace_1"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.value").value("x"))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.meta.requestId").value("client-trace_1"))
                .andExpect(jsonPath("$.meta.partial").doesNotExist())
                .andExpect(jsonPath("$.meta.warnings").doesNotExist())
                // ISO-8601 string, not epoch numbers.
                .andExpect(jsonPath("$.timestamp").value(containsString("T")));
    }

    @Test void invalidRequestIdIsReplacedAndSameValueUsedInHeaderAndBody() throws Exception {
        var result = throwing(new IllegalArgumentException("잘못"))
                .andExpect(header().string(RequestIdFilter.HEADER, not("bad id!")))
                .andReturn();
        String header = result.getResponse().getHeader(RequestIdFilter.HEADER);
        mvc.perform(get("/probe/ok").header(RequestIdFilter.HEADER, "bad id!"))
                .andExpect(header().string(RequestIdFilter.HEADER, not("bad id!")));
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString()).contains("\"requestId\":\"" + header + "\"");
    }

    @Test void apiExceptionUsesItsCodeStatusAndDetails() throws Exception {
        assertFailureEnvelope(throwing(new ApiException(ErrorCode.PLACE_QUERY_REQUIRED, null,
                List.of(new Detail("query", "필수입니다.")))), HttpStatus.BAD_REQUEST, "PLACE_QUERY_REQUIRED");
        throwing(new ApiException(ErrorCode.PLACE_QUERY_REQUIRED, null, List.of(new Detail("query", "필수입니다."))))
                .andExpect(jsonPath("$.error.details[0].field").value("query"))
                .andExpect(jsonPath("$.error.details[0].reason").value("필수입니다."))
                .andExpect(jsonPath("$.error.message").value(ErrorCode.PLACE_QUERY_REQUIRED.defaultMessage()));
        assertFailureEnvelope(throwing(new ApiException(ErrorCode.PLACE_PROVIDER_ERROR, "장소 검색 응답을 가져오지 못했습니다.")),
                HttpStatus.BAD_GATEWAY, "PLACE_PROVIDER_ERROR");
        assertFailureEnvelope(throwing(new ApiException(ErrorCode.SERVICE_UNAVAILABLE)),
                HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
    }

    @ParameterizedTest
    @CsvSource({
            "ROUTING_NOT_CONFIGURED, SERVICE_UNAVAILABLE, 503",
            "NO_RENTAL_STATION, NO_AVAILABLE_ORIGIN_STATION, 422",
            "NO_RENTAL_BIKES, NO_AVAILABLE_ORIGIN_STATION, 422",
            "PINNED_STATION_EMPTY, NO_AVAILABLE_ORIGIN_STATION, 422",
            "NO_RETURN_STATION, NO_AVAILABLE_RETURN_STATION, 422",
            "NO_CONNECTED_ROUTE, ROUTE_NOT_FOUND, 404",
            "NO_ROAD_ROUTE, ROUTE_NOT_FOUND, 404",
            "PINNED_STATION_NOT_FOUND, STATION_NOT_FOUND, 404",
            "STATION_STATUS_UNAVAILABLE, REALTIME_DATA_UNAVAILABLE, 503",
            "RENTAL_STOCK_CHANGED, RENTAL_STOCK_CHANGED, 409",
            "ROUTING_UNAVAILABLE, BIKE_ROUTE_PROVIDER_ERROR, 502",
            "ROUTING_LIMIT, BIKE_ROUTE_PROVIDER_ERROR, 502",
            "ROUTING_AUTH, BIKE_ROUTE_PROVIDER_ERROR, 502",
            // Codes E2 will throw directly pass through unchanged.
            "WALK_ROUTE_PROVIDER_ERROR, WALK_ROUTE_PROVIDER_ERROR, 502",
            "BIKE_ROUTE_PROVIDER_ERROR, BIKE_ROUTE_PROVIDER_ERROR, 502",
    })
    void routeExceptionsMapToSpecCodesAndTheirStatus(String thrown, String expected, int status) throws Exception {
        // The status carried by the exception is deliberately wrong: the public code decides.
        assertFailureEnvelope(throwing(new RouteException(HttpStatus.ACCEPTED, thrown, "사용자 메시지")),
                HttpStatus.valueOf(status), expected);
        throwing(new RouteException(HttpStatus.ACCEPTED, thrown, "사용자 메시지"))
                .andExpect(jsonPath("$.error.message").value("사용자 메시지"));
    }

    @Test void unknownRouteCodeIsInternalErrorWithoutLeakingMessage() throws Exception {
        assertFailureEnvelope(throwing(new RouteException(HttpStatus.BAD_REQUEST, "SOMETHING_NEW", "internal-detail")),
                HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR");
        throwing(new RouteException(HttpStatus.BAD_REQUEST, "SOMETHING_NEW", "internal-detail"))
                .andExpect(content().string(not(containsString("internal-detail"))));
    }

    @Test void illegalArgumentsAreBadRequestsAndCoordinatesAreDistinguished() throws Exception {
        assertFailureEnvelope(throwing(new Location.InvalidCoordinateException("latitude 범위가 올바르지 않습니다.")),
                HttpStatus.BAD_REQUEST, "INVALID_COORDINATE");
        assertFailureEnvelope(throwing(new IllegalArgumentException("지도 조회 영역이 올바르지 않습니다.")),
                HttpStatus.BAD_REQUEST, "INVALID_PARAMETER");
    }

    @Test void dataStoreFailuresAreServiceUnavailableWithoutInternals() throws Exception {
        assertFailureEnvelope(throwing(new DataAccessResourceFailureException("jdbc:mysql://secret-host")),
                HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
        throwing(new DataAccessResourceFailureException("jdbc:mysql://secret-host"))
                .andExpect(content().string(not(containsString("secret-host"))));
    }

    @Test void unexpectedErrorsAreInternalWithoutInternals() throws Exception {
        assertFailureEnvelope(throwing(new IllegalStateException("NullPointer in com.ddareungi.Secret")),
                HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR");
        throwing(new IllegalStateException("NullPointer in com.ddareungi.Secret"))
                .andExpect(content().string(not(containsString("Secret"))));
    }

    @Test void missingAndMismatchedParametersAreInvalidParameter() throws Exception {
        assertFailureEnvelope(mvc.perform(get("/probe/params")), HttpStatus.BAD_REQUEST, "INVALID_PARAMETER");
        mvc.perform(get("/probe/params")).andExpect(jsonPath("$.error.details[0].field").value("count"));
        assertFailureEnvelope(mvc.perform(get("/probe/params").param("count", "abc")), HttpStatus.BAD_REQUEST, "INVALID_PARAMETER");
        mvc.perform(get("/probe/params").param("count", "abc"))
                .andExpect(jsonPath("$.error.details[0].field").value("count"))
                // The rejected input is never echoed back.
                .andExpect(content().string(not(containsString("abc"))));
    }

    @Test void bodyValidationReportsLastFieldNameAndCoordinateCode() throws Exception {
        assertFailureEnvelope(mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":91,\"name\":\"a\"}")), HttpStatus.BAD_REQUEST, "INVALID_COORDINATE");
        mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{\"latitude\":91,\"name\":\"a\"}"))
                .andExpect(jsonPath("$.error.details[0].field").value("latitude"));
        assertFailureEnvelope(mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":1,\"name\":\" \"}")), HttpStatus.BAD_REQUEST, "INVALID_PARAMETER");
    }

    @Test void unreadableJsonIsInvalidParameterUnlessTheCauseIsACoordinate() throws Exception {
        assertFailureEnvelope(mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{oops")),
                HttpStatus.BAD_REQUEST, "INVALID_PARAMETER");
        assertFailureEnvelope(mvc.perform(post("/probe/location").contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":95,\"longitude\":127}")), HttpStatus.BAD_REQUEST, "INVALID_COORDINATE");
    }

    @Test void unsupportedMediaTypeAndMethodAndUnknownPath() throws Exception {
        assertFailureEnvelope(mvc.perform(post("/probe/body").contentType(MediaType.TEXT_PLAIN).content("x")),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");
        assertFailureEnvelope(mvc.perform(put("/probe/ok")), HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED");
        mvc.perform(put("/probe/ok")).andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")));
        assertFailureEnvelope(mvc.perform(get("/probe/missing")), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }
}
