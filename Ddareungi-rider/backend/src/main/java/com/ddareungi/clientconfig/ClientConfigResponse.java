package com.ddareungi.clientconfig;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * {@code data} of {@code GET /api/client-config} (spec 6). Nullable fields are always written as null, never
 * omitted. For {@code web}: versions and store URL are null and both update flags false.
 */
public record ClientConfigResponse(
        String platform,
        @Nullable String minimumVersion,
        @Nullable String latestVersion,
        boolean forceUpdate,
        boolean updateAvailable,
        boolean maintenance,
        @Nullable String maintenanceMessage,
        List<String> supportedApiVersions,
        @Nullable String storeUrl
) {}
