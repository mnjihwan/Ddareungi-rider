package com.ddareungi.global.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Assigns every request a trace id (spec 7): a valid client {@code X-Request-Id} is reused, anything else is
 * replaced by a UUID. The id is written to the response header before the chain runs, so error responses carry it
 * too, and it is exposed to logs (MDC {@code requestId}) and to response bodies ({@code meta.requestId}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolve(request.getHeader(HEADER));
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        MDC.put(MDC_KEY, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** Reuses a client id only when it matches the allowed format; otherwise generates a new UUID. */
    static String resolve(@Nullable String candidate) {
        return candidate != null && VALID.matcher(candidate).matches() ? candidate : UUID.randomUUID().toString();
    }

    /** The id of the request being handled on this thread, or null outside a request. */
    public static @Nullable String current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null && attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof String id) {
            return id;
        }
        return MDC.get(MDC_KEY);
    }
}
