package com.ddareungi.global.exception;

import com.ddareungi.global.response.ApiErrorResponse.Detail;
import org.jspecify.annotations.Nullable;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * An error with a public {@link ErrorCode}. It stays a {@link ResponseStatusException} so callers and tests that
 * only look at the HTTP status keep working. The message must be safe to show to users (no secrets, raw input).
 */
public class ApiException extends ResponseStatusException {
    private final ErrorCode errorCode;
    private final List<Detail> details;

    public ApiException(ErrorCode errorCode) { this(errorCode, null, List.of()); }

    public ApiException(ErrorCode errorCode, @Nullable String message) { this(errorCode, message, List.of()); }

    public ApiException(ErrorCode errorCode, @Nullable String message, List<Detail> details) {
        super(errorCode.status(), message == null ? errorCode.defaultMessage() : message);
        this.errorCode = errorCode;
        this.details = List.copyOf(details);
    }

    public ErrorCode errorCode() { return errorCode; }
    public List<Detail> details() { return details; }
}
