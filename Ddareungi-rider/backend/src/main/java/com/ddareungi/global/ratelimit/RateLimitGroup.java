package com.ddareungi.global.ratelimit;

import org.jspecify.annotations.Nullable;

/**
 * Policy groups (phase 2 D6). Classification prefers the matched handler pattern, so path variants that still reach
 * a controller (trailing slash, encoded characters, ...) land in the same group.
 */
public enum RateLimitGroup {
    /** {@code /api/v1/stations/**}, {@code /api/v1/status/**} and {@code /api/v1/routes/status}: cheap reads. */
    STATIONS("stations"),
    /** {@code /api/v1/places/**}: each call can cost several Kakao requests. */
    PLACES("places"),
    /** {@code /api/v1/routes/**} except status: route planning, the most expensive API. */
    ROUTES("routes");

    private final String key;

    RateLimitGroup(String key) { this.key = key; }

    /** Stable name used in storage keys and logs. */
    public String key() { return key; }

    /** The group of an application path, or null when the path is not rate limited. */
    public static @Nullable RateLimitGroup classify(@Nullable String path) {
        if (path == null) return null;
        if (under(path, "/api/v1/routes/status")) return STATIONS;
        if (under(path, "/api/v1/stations") || under(path, "/api/v1/status")) return STATIONS;
        if (under(path, "/api/v1/places")) return PLACES;
        if (under(path, "/api/v1/routes")) return ROUTES;
        return null;
    }

    private static boolean under(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
