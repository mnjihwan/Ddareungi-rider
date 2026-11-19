package com.ddareungi.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * Exact browser origins allowed to call /api/**. Empty means no CORS mapping (same-origin only).
 * Wildcards and patterns are rejected at startup: production must never answer with "*".
 */
@ConfigurationProperties("app.cors")
public record CorsProperties(@DefaultValue List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of()
                : allowedOrigins.stream().map(String::trim).filter(origin -> !origin.isEmpty()).toList();
        allowedOrigins.forEach(CorsProperties::requireExactOrigin);
    }

    private static void requireExactOrigin(String origin) {
        if (origin.contains("*")) {
            throw new IllegalArgumentException("app.cors.allowed-origins must not contain wildcards: " + origin);
        }
        URI uri;
        try {
            uri = new URI(origin);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("app.cors.allowed-origins has an invalid origin: " + origin, exception);
        }
        String scheme = uri.getScheme();
        boolean exact = ("http".equals(scheme) || "https".equals(scheme))
                && uri.getHost() != null && uri.getRawUserInfo() == null
                && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                && uri.getRawQuery() == null && uri.getRawFragment() == null;
        if (!exact) {
            throw new IllegalArgumentException(
                    "app.cors.allowed-origins entries must be exact scheme://host[:port] origins: " + origin);
        }
    }
}
