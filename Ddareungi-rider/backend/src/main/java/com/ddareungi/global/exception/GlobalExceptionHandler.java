package com.ddareungi.global.exception;

import com.ddareungi.global.response.ApiErrorResponse;
import com.ddareungi.global.response.ApiErrorResponse.Detail;
import com.ddareungi.global.web.RequestIdFilter;
import com.ddareungi.routing.RouteException;
import com.ddareungi.station.domain.Location.InvalidCoordinateException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts every API error into the failure envelope (spec 8.2) with a spec 10 code. Framework exceptions
 * (missing/mismatched parameters, unreadable JSON, 404/405/406/415, ...) arrive through
 * {@link #handleExceptionInternal}. Internal details only go to the log, tagged with the request id.
 * Not applied by design: Actuator success responses, 204 responses and CORS rejections (403 written by Spring).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final Set<String> COORDINATE_FIELDS = Set.of("latitude", "longitude");

    /** Codes thrown by routing before the provider split (E2) and their public equivalent (phase 1 table 2.5). */
    static final Map<String, ErrorCode> LEGACY_ROUTE_CODES = Map.ofEntries(
            Map.entry("ROUTING_NOT_CONFIGURED", ErrorCode.SERVICE_UNAVAILABLE),
            Map.entry("NO_RENTAL_STATION", ErrorCode.NO_AVAILABLE_ORIGIN_STATION),
            Map.entry("NO_RENTAL_BIKES", ErrorCode.NO_AVAILABLE_ORIGIN_STATION),
            Map.entry("PINNED_STATION_EMPTY", ErrorCode.NO_AVAILABLE_ORIGIN_STATION),
            Map.entry("NO_RETURN_STATION", ErrorCode.NO_AVAILABLE_RETURN_STATION),
            Map.entry("NO_CONNECTED_ROUTE", ErrorCode.ROUTE_NOT_FOUND),
            Map.entry("NO_ROAD_ROUTE", ErrorCode.ROUTE_NOT_FOUND),
            Map.entry("PINNED_STATION_NOT_FOUND", ErrorCode.STATION_NOT_FOUND),
            Map.entry("STATION_STATUS_UNAVAILABLE", ErrorCode.REALTIME_DATA_UNAVAILABLE),
            // Temporary until E2 throws WALK_/BIKE_ROUTE_PROVIDER_ERROR per leg.
            Map.entry("ROUTING_UNAVAILABLE", ErrorCode.BIKE_ROUTE_PROVIDER_ERROR),
            Map.entry("ROUTING_LIMIT", ErrorCode.BIKE_ROUTE_PROVIDER_ERROR),
            Map.entry("ROUTING_AUTH", ErrorCode.BIKE_ROUTE_PROVIDER_ERROR));

    private final Clock clock;

    public GlobalExceptionHandler(ObjectProvider<Clock> clock) {
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApi(ApiException exception, WebRequest request) {
        if (exception.errorCode().status().is5xxServerError()) {
            // ApiException messages are already sanitized; a stack trace only helps when there is a cause.
            if (exception.getCause() != null) {
                log.warn("API error {} (requestId={})", exception.errorCode(), requestId(request), exception);
            } else {
                log.warn("API error {} (requestId={})", exception.errorCode(), requestId(request));
            }
        }
        return respond(exception.errorCode(), exception.getReason(), exception.details(), exception.getHeaders(), request);
    }

    @ExceptionHandler(RouteException.class)
    public ResponseEntity<Object> handleRoute(RouteException exception, WebRequest request) {
        ErrorCode code = routeCode(exception.code);
        if (code == null) {
            log.warn("Unmapped route error code {} (requestId={})", exception.code, requestId(request), exception);
            return respond(ErrorCode.INTERNAL_SERVER_ERROR, null, List.of(), null, request);
        }
        if (code.status().is5xxServerError()) {
            log.warn("Route error {} -> {} (requestId={})", exception.code, code, requestId(request));
        }
        // HTTP status follows the public code, not the status carried by the exception.
        return respond(code, exception.getMessage(), List.of(), null, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException exception, WebRequest request) {
        var details = new ArrayList<Detail>();
        for (ConstraintViolation<?> violation : exception.getConstraintViolations()) {
            details.add(new Detail(lastNode(violation.getPropertyPath()), violation.getMessage()));
        }
        return respond(validationCode(details), null, details, null, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> handleIllegalArgument(IllegalArgumentException exception, WebRequest request) {
        // Kept from before phase 1: domain validation throws IAE with a user-facing message. Risk: an internal IAE
        // also becomes 400.
        ErrorCode code = exception instanceof InvalidCoordinateException
                ? ErrorCode.INVALID_COORDINATE : ErrorCode.INVALID_PARAMETER;
        return respond(code, exception.getMessage(), List.of(), null, request);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Object> handleDataAccess(DataAccessException exception, WebRequest request) {
        log.warn("Data store unavailable (requestId={})", requestId(request), exception);
        return respond(ErrorCode.SERVICE_UNAVAILABLE, null, List.of(), null, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception exception, WebRequest request) {
        log.error("Unexpected error (requestId={})", requestId(request), exception);
        return respond(ErrorCode.INTERNAL_SERVER_ERROR, null, List.of(), null, request);
    }

    /** All Spring MVC exceptions handled by the base class end here; their HTTP status is kept. */
    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(Exception exception, @Nullable Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (request instanceof ServletWebRequest servlet && servlet.getResponse() != null
                && servlet.getResponse().isCommitted()) {
            return null;
        }
        List<Detail> details = List.of();
        ErrorCode code;
        String message = null;
        switch (exception) {
            case MethodArgumentNotValidException invalid -> {
                details = bindingDetails(invalid);
                code = validationCode(details);
            }
            case HandlerMethodValidationException invalid -> {
                details = methodValidationDetails(invalid);
                code = validationCode(details);
            }
            case MissingServletRequestParameterException missing -> {
                details = List.of(new Detail(missing.getParameterName(), "필수 파라미터입니다."));
                code = ErrorCode.INVALID_PARAMETER;
            }
            case TypeMismatchException mismatch -> {
                details = List.of(new Detail(mismatch.getPropertyName() == null ? "" : mismatch.getPropertyName(),
                        "형식이 올바르지 않습니다."));
                code = ErrorCode.INVALID_PARAMETER;
            }
            case HttpMessageNotReadableException unreadable -> {
                code = causedByInvalidCoordinate(unreadable) ? ErrorCode.INVALID_COORDINATE : ErrorCode.INVALID_PARAMETER;
                if (code == ErrorCode.INVALID_PARAMETER) message = "요청 본문을 읽을 수 없습니다.";
            }
            default -> code = codeForStatus(statusCode);
        }
        if (statusCode.is5xxServerError()) {
            log.warn("Framework error {} (requestId={})", statusCode.value(), requestId(request), exception);
        }
        // Keep the framework's status (e.g. 405 with its Allow header) and pick the matching public code.
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.addAll(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        return ResponseEntity.status(statusCode).headers(responseHeaders)
                .body(errorBody(code, message, details, request));
    }

    static @Nullable ErrorCode routeCode(@Nullable String code) {
        if (code == null) return null;
        for (ErrorCode candidate : ErrorCode.values()) {
            if (candidate.name().equals(code)) return candidate;
        }
        return LEGACY_ROUTE_CODES.get(code);
    }

    private static ErrorCode codeForStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ErrorCode.INVALID_PARAMETER;
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> ErrorCode.NOT_ACCEPTABLE;
            case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 503 -> ErrorCode.SERVICE_UNAVAILABLE;
            default -> status.is4xxClientError() ? ErrorCode.INVALID_PARAMETER : ErrorCode.INTERNAL_SERVER_ERROR;
        };
    }

    /** Coordinate-only violations are INVALID_COORDINATE, radius-only INVALID_RADIUS, anything else INVALID_PARAMETER. */
    private static ErrorCode validationCode(List<Detail> details) {
        if (details.isEmpty()) return ErrorCode.INVALID_PARAMETER;
        if (details.stream().allMatch(detail -> COORDINATE_FIELDS.contains(detail.field()))) return ErrorCode.INVALID_COORDINATE;
        if (details.stream().allMatch(detail -> "radiusMeters".equals(detail.field()))) return ErrorCode.INVALID_RADIUS;
        return ErrorCode.INVALID_PARAMETER;
    }

    private static List<Detail> bindingDetails(MethodArgumentNotValidException exception) {
        var details = new ArrayList<Detail>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            details.add(new Detail(lastNode(error.getField()), reason(error.getDefaultMessage())));
        }
        exception.getBindingResult().getGlobalErrors().forEach(error ->
                details.add(new Detail(error.getObjectName(), reason(error.getDefaultMessage()))));
        return details;
    }

    private static List<Detail> methodValidationDetails(HandlerMethodValidationException exception) {
        var details = new ArrayList<Detail>();
        exception.getParameterValidationResults().forEach(result -> {
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error ->
                    details.add(new Detail(name == null ? "" : name, reason(error.getDefaultMessage()))));
        });
        return details;
    }

    private static boolean causedByInvalidCoordinate(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidCoordinateException) return true;
            if (cause.getCause() == cause) break;
        }
        return false;
    }

    private static String lastNode(Path path) {
        String last = "";
        for (Path.Node node : path) {
            if (node.getName() != null) last = node.getName();
        }
        return last;
    }

    private static String lastNode(String path) {
        String last = path.substring(path.lastIndexOf('.') + 1);
        int bracket = last.indexOf('[');
        return bracket >= 0 ? last.substring(0, bracket) : last;
    }

    private static String reason(@Nullable String message) {
        return message == null ? "올바르지 않은 값입니다." : message;
    }

    private ResponseEntity<Object> respond(ErrorCode code, @Nullable String message, List<Detail> details,
            @Nullable HttpHeaders headers, WebRequest request) {
        var builder = ResponseEntity.status(code.status());
        if (headers != null) builder.headers(headers);
        return builder.contentType(MediaType.APPLICATION_JSON).body(errorBody(code, message, details, request));
    }

    private ApiErrorResponse errorBody(ErrorCode code, @Nullable String message, List<Detail> details, WebRequest request) {
        String safeMessage = message == null || message.isBlank() ? code.defaultMessage() : message;
        return ApiErrorResponse.of(code, safeMessage, details, requestId(request), clock.instant());
    }

    private static @Nullable String requestId(WebRequest request) {
        if (request.getAttribute(RequestIdFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof String id) return id;
        return RequestIdFilter.current();
    }
}
