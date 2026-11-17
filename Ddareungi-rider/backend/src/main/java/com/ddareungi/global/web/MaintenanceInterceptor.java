package com.ddareungi.global.web;

import com.ddareungi.global.exception.ApiException;
import com.ddareungi.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Answers {@code 503 MAINTENANCE_MODE} while maintenance is on (decision 21). It throws an {@link ApiException}, so
 * the response is the usual error envelope with {@code meta.requestId}, and as a handler interceptor it runs after
 * Spring's CORS processing (cross-origin callers can read the error). CORS preflight is never blocked.
 *
 * <p>Ordering: {@link DeprecationInterceptor#ORDER} &lt; {@link #ORDER} &lt; 0 (rate limit, default order), so a
 * blocked request still carries deprecation headers and never reaches the rate limiter.
 */
public class MaintenanceInterceptor implements HandlerInterceptor {
    public static final int ORDER = -100;

    private final boolean maintenance;
    private final String message;

    public MaintenanceInterceptor(boolean maintenance, String message) {
        this.maintenance = maintenance;
        this.message = message;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!maintenance || HttpMethod.OPTIONS.matches(request.getMethod())) return true;
        throw new ApiException(ErrorCode.MAINTENANCE_MODE, message);
    }
}
