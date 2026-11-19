package com.ddareungi.global.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * One access log line per request (spec 33, phase 3 O8), written after the response:
 * {@code method=GET path=/api/v1/stations/nearby status=200 elapsedMs=42}. The same four fields are attached as
 * SLF4J key-value pairs, so the infra profile's structured (ECS JSON) console log carries them as fields; the request
 * id comes from the MDC set by {@link RequestIdFilter}, which runs first.
 *
 * <p>Privacy (spec 26): only {@link HttpServletRequest#getRequestURI()} is logged, never the query string (nearby
 * search coordinates, place queries) or bodies (route origins). Actuator paths are not logged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AccessLogFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AccessLogFilter.class);
    static final int MAX_PATH_LENGTH = 256;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || path.equals("/actuator") || path.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        boolean failed = true;
        try {
            chain.doFilter(request, response);
            failed = false;
        } finally {
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            int status = response.getStatus();
            // An exception escaping the chain becomes a 500 in the container even though the status is still unset.
            if (failed && status < 400) status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            if (log.isInfoEnabled()) {
                String method = request.getMethod();
                String path = path(request.getRequestURI());
                log.atInfo()
                        .setMessage("method={} path={} status={} elapsedMs={}")
                        .addArgument(method).addArgument(path).addArgument(status).addArgument(elapsedMs)
                        .addKeyValue("method", method)
                        .addKeyValue("path", path)
                        .addKeyValue("status", status)
                        .addKeyValue("elapsedMs", elapsedMs)
                        .log();
            }
        }
    }

    /** The URI path without query, truncated and with control characters replaced (no log line injection). */
    static String path(@Nullable String uri) {
        if (uri == null) return "";
        int query = uri.indexOf('?'); // getRequestURI never has one; defensive
        String path = query >= 0 ? uri.substring(0, query) : uri;
        if (path.length() > MAX_PATH_LENGTH) path = path.substring(0, MAX_PATH_LENGTH) + "...";
        StringBuilder clean = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            clean.append(c < 0x20 || c == 0x7f ? '?' : c);
        }
        return clean.toString();
    }
}
