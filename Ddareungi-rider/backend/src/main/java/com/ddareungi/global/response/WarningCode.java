package com.ddareungi.global.response;

/**
 * Allowed {@code meta.warnings[].code} values (spec v5 11.0). Warnings never carry user location or raw storage
 * errors. {@code FALLBACK_USED} is reserved for the MySQL fallback (not implemented while R2 writes are on hold).
 * {@code RESULT_TRUNCATED} (R4) marks a map response cut at its item cap (e.g. viewport: the nearest 500 to the
 * bounds centre are returned).
 */
public enum WarningCode {
    FALLBACK_USED,
    STALE_DATA,
    REALTIME_DATA_UNAVAILABLE,
    STATION_EXCLUDED,
    CANDIDATE_DATA_UNAVAILABLE,
    PROVIDER_PARTIAL_FAILURE,
    RESULT_TRUNCATED
}
