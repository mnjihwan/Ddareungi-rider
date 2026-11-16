package com.ddareungi.global.response;

import com.ddareungi.global.web.RequestIdFilter;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;

/**
 * Success envelope (spec 8.1): {@code {success:true, data, meta:{requestId}, timestamp}}. Never carries
 * {@code error}. Built explicitly by controllers; there is deliberately no ResponseBodyAdvice auto-wrapping, so
 * Actuator and other framework responses keep their own format.
 */
public record ApiResponse<T>(boolean success, @Nullable T data, Meta meta, Instant timestamp) {
    public static <T> ApiResponse<T> ok(@Nullable T data) { return ok(data, Clock.systemUTC()); }

    /**
     * Success with explicit metadata ({@code partial}/{@code warnings}). A null {@code meta.requestId} is filled from
     * the current request, so callers normally pass {@link Meta#partial} or {@code Meta.of(null)}.
     */
    public static <T> ApiResponse<T> ok(@Nullable T data, Meta meta) {
        Meta resolved = meta.requestId() == null ? meta.withRequestId(RequestIdFilter.current()) : meta;
        return new ApiResponse<>(true, data, resolved, Clock.systemUTC().instant());
    }

    /** requestId comes from the current request (null outside one); the clock is injectable for tests. */
    public static <T> ApiResponse<T> ok(@Nullable T data, Clock clock) {
        return new ApiResponse<>(true, data, Meta.of(RequestIdFilter.current()), clock.instant());
    }
}
