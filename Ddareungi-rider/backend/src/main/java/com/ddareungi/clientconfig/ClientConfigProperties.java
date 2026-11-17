package com.ddareungi.clientconfig;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Client version and maintenance policy (spec 6, phase 3 A7). Checked at startup: versions must be
 * {@code major.minor.patch}, {@code minimum-version <= latest-version} per platform, store URLs absolute http(s),
 * supported API versions {@code v<number>}.
 *
 * <ul>
 *   <li>{@code client.web.maintenance} / {@code maintenance-message}: the server-wide maintenance switch (decision 21,
 *   env {@code CLIENT_WEB_MAINTENANCE}). It is reported to every platform and blocks {@code /api/v1/**} with
 *   {@code 503 MAINTENANCE_MODE}; a blank message falls back to the default text of that code.</li>
 *   <li>{@code client.platforms.android|ios}: {@code minimum-version}, {@code latest-version} (defaults
 *   {@code 0.1.0}, the PoC app version) and {@code store-url} (default none).</li>
 *   <li>{@code client.supported-api-versions}: default {@code [v1]}.</li>
 * </ul>
 */
@ConfigurationProperties("client")
public record ClientConfigProperties(
        @DefaultValue Web web,
        @DefaultValue Platforms platforms,
        @DefaultValue("v1") List<String> supportedApiVersions
) {
    private static final Pattern API_VERSION = Pattern.compile("v[1-9][0-9]{0,2}");

    public ClientConfigProperties {
        supportedApiVersions = supportedApiVersions == null ? List.of()
                : supportedApiVersions.stream().map(String::trim).filter(value -> !value.isEmpty()).toList();
        if (supportedApiVersions.isEmpty()) {
            throw new IllegalArgumentException("client.supported-api-versions must not be empty");
        }
        for (String version : supportedApiVersions) {
            if (!API_VERSION.matcher(version).matches()) {
                throw new IllegalArgumentException("client.supported-api-versions entries must look like v1: " + version);
            }
        }
    }

    public record Web(@DefaultValue("false") boolean maintenance, @Nullable String maintenanceMessage) {
        public Web {
            maintenanceMessage = maintenanceMessage == null || maintenanceMessage.isBlank() ? null
                    : maintenanceMessage.strip();
        }
    }

    public record Platforms(@DefaultValue Mobile android, @DefaultValue Mobile ios) {
        public Platforms {
            android.validate("client.platforms.android");
            ios.validate("client.platforms.ios");
        }

        public Mobile of(ClientPlatform platform) {
            return switch (platform) {
                case ANDROID -> android;
                case IOS -> ios;
                case WEB -> throw new IllegalArgumentException("web has no version policy");
            };
        }
    }

    public record Mobile(
            @DefaultValue("0.1.0") String minimumVersion,
            @DefaultValue("0.1.0") String latestVersion,
            @Nullable String storeUrl
    ) {
        public Mobile {
            minimumVersion = minimumVersion == null ? "" : minimumVersion.strip();
            latestVersion = latestVersion == null ? "" : latestVersion.strip();
            storeUrl = storeUrl == null || storeUrl.isBlank() ? null : storeUrl.strip();
        }

        public AppVersion minimum() { return AppVersion.tryParse(minimumVersion).orElseThrow(); }
        public AppVersion latest() { return AppVersion.tryParse(latestVersion).orElseThrow(); }

        void validate(String prefix) {
            AppVersion minimum = AppVersion.tryParse(minimumVersion).orElseThrow(() -> new IllegalArgumentException(
                    prefix + ".minimum-version must be major.minor.patch: " + minimumVersion));
            AppVersion latest = AppVersion.tryParse(latestVersion).orElseThrow(() -> new IllegalArgumentException(
                    prefix + ".latest-version must be major.minor.patch: " + latestVersion));
            if (latest.isBefore(minimum)) {
                throw new IllegalArgumentException(prefix + ".minimum-version (" + minimum
                        + ") must not be greater than latest-version (" + latest + ")");
            }
            if (storeUrl != null) requireHttpUrl(prefix + ".store-url", storeUrl);
        }
    }

    private static void requireHttpUrl(String name, String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(name + " is not a valid URL: " + value, exception);
        }
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null) {
            throw new IllegalArgumentException(name + " must be an absolute http(s) URL: " + value);
        }
    }
}
