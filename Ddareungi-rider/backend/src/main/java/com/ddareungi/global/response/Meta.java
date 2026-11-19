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

    /**
     * {@code partial=true} with the given warning codes (duplicates removed, order kept). The requestId is left
     * null for {@link ApiResponse#ok(Object, Meta)} to fill from the current request.
     */
    public static Meta partial(List<WarningCode> codes) {
        return new Meta(null, true, codes.stream().distinct().map(Warning::of).toList());
    }

    /** Copy with the requestId replaced. */
    public Meta withRequestId(@Nullable String requestId) { return new Meta(requestId, partial, warnings); }

    public record Warning(String code) {
        public static Warning of(WarningCode code) { return new Warning(code.name()); }
    }
}
