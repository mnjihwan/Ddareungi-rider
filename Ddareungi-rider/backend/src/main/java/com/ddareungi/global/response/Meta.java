package com.ddareungi.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** Response metadata (spec 8). {@code requestId} is always written; {@code partial}/{@code warnings} only when set. */
public record Meta(
        @Nullable String requestId,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Boolean partial,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable List<Warning> warnings
) {
    public static Meta of(@Nullable String requestId) { return new Meta(requestId, null, null); }

    public record Warning(String code) {}
}
