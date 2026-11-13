package com.ddareungi.global.response;

import com.ddareungi.global.exception.ErrorCode;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/** Failure envelope (spec 8.2/8.3): {@code {success:false, error:{code,message,details}, meta, timestamp}}, no data. */
public record ApiErrorResponse(boolean success, ErrorBody error, Meta meta, Instant timestamp) {
    public static ApiErrorResponse of(ErrorCode code, String message, List<Detail> details,
            @Nullable String requestId, Instant timestamp) {
        return new ApiErrorResponse(false, new ErrorBody(code.name(), message, List.copyOf(details)),
                Meta.of(requestId), timestamp);
    }

    public record ErrorBody(String code, String message, List<Detail> details) {}

    /** One invalid input: {@code field} is the last path node (e.g. {@code latitude}), never the rejected value. */
    public record Detail(String field, String reason) {}
}
