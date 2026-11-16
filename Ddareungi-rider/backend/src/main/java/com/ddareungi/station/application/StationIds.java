package com.ddareungi.station.application;

import com.ddareungi.global.exception.ApiException;
import com.ddareungi.global.exception.ErrorCode;
import com.ddareungi.global.response.ApiErrorResponse.Detail;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Public stationId format and the {@code stationIds} batch parameter (spec 11.3, D14).
 *
 * <p>D14: {@value #PATTERN} until the real ids are confirmed; the spec's stricter {@code ST-}+digits form is not
 * enforced yet. Error messages never echo the rejected input.
 */
public final class StationIds {
    /** Allowed stationId: 1–30 ASCII letters, digits or hyphens. */
    public static final String PATTERN = "^[A-Za-z0-9-]{1,30}$";
    public static final int MAX_BATCH_SIZE = 100;
    private static final Pattern ID = Pattern.compile(PATTERN);

    private StationIds() {}

    public static boolean valid(String id) { return id != null && ID.matcher(id).matches(); }

    /** A single path id (detail API); 400 {@code INVALID_PARAMETER} on field {@code stationId} when malformed. */
    public static String requireValid(String id) {
        if (!valid(id)) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, null,
                    List.of(new Detail("stationId", "영문·숫자·하이픈 1~30자여야 합니다.")));
        }
        return id;
    }

    /**
     * Splits a comma-separated {@code stationIds} value. 1–{@value #MAX_BATCH_SIZE} entries counted before duplicates
     * are removed; an empty entry, surrounding whitespace or a malformed id is 400 {@code INVALID_PARAMETER}.
     *
     * @return distinct ids in first-occurrence order
     */
    public static List<String> parseBatch(String raw) {
        if (raw == null || raw.isEmpty()) throw invalid("stationIds를 1개 이상 입력해 주세요.");
        String[] parts = raw.split(",", -1);
        if (parts.length > MAX_BATCH_SIZE) throw invalid("stationIds는 최대 " + MAX_BATCH_SIZE + "개까지 요청할 수 있습니다.");
        var distinct = new LinkedHashSet<String>();
        for (String part : parts) {
            if (!valid(part)) throw invalid("stationIds 항목은 비어 있지 않은 영문·숫자·하이픈 1~30자여야 합니다.");
            distinct.add(part);
        }
        return List.copyOf(distinct);
    }

    private static ApiException invalid(String reason) {
        return new ApiException(ErrorCode.INVALID_PARAMETER, null, List.of(new Detail("stationIds", reason)));
    }
}
