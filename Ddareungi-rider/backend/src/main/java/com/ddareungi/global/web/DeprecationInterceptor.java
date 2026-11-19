package com.ddareungi.global.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Adds the {@code Deprecation}/{@code Sunset} (and optional {@code Link}) headers of one deprecated path before
 * anything else runs, so error responses produced later (validation, 429, 503, provider errors) keep them. Requests
 * rejected before handler lookup (e.g. 405 for an unsupported method) do not reach interceptors and carry no
 * deprecation headers. Both header names are already exposed by the CORS configuration.
 */
public class DeprecationInterceptor implements HandlerInterceptor {
    public static final int ORDER = InterceptorOrder.DEPRECATION;
    public static final String DEPRECATION = "Deprecation";
    public static final String SUNSET = "Sunset";

    private final String deprecation;
    private final String sunset;
    private final @Nullable String link;

    public DeprecationInterceptor(DeprecationProperties.Entry entry) {
        this.deprecation = entry.deprecationHeader();
        this.sunset = entry.sunsetHeader();
        this.link = entry.linkHeader();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        response.setHeader(DEPRECATION, deprecation);
        response.setHeader(SUNSET, sunset);
        if (link != null) response.addHeader(HttpHeaders.LINK, link);
        return true;
    }
}
