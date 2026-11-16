package com.ddareungi.global.response;

/**
 * Allowed {@code meta.warnings[].code} values (spec v5 11.0). Warnings never carry user location or raw storage
 * errors. {@code FALLBACK_USED} is reserved for the MySQL fallback (not implemented while R2 writes are on hold).
 */
public enum WarningCode {
    FALLBACK_USED,
    STALE_DATA,
    REALTIME_DATA_UNAVAILABLE,
    STATION_EXCLUDED,
    CANDIDATE_DATA_UNAVAILABLE,
    PROVIDER_PARTIAL_FAILURE
}
