package com.ddareungi.clientconfig;

import org.jspecify.annotations.Nullable;

/** The {@code platform} query value of {@code GET /api/client-config} (spec 6). Values are lower case only. */
public enum ClientPlatform {
    ANDROID("android"),
    IOS("ios"),
    WEB("web");

    private final String value;

    ClientPlatform(String value) { this.value = value; }

    public String value() { return value; }

    public boolean mobile() { return this != WEB; }

    /** The platform for an exact query value, or null when the value is missing or unknown. */
    public static @Nullable ClientPlatform from(@Nullable String value) {
        if (value == null) return null;
        for (ClientPlatform platform : values()) {
            if (platform.value.equals(value)) return platform;
        }
        return null;
    }
}
