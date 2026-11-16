package com.ddareungi.global.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Applies the group policies before the controller runs. It is a handler interceptor, not a servlet filter, so it
 * runs after Spring's CORS processing: a cross-origin 429 still carries the CORS headers and exposes
 * {@code Retry-After}, and the error goes through {@code GlobalExceptionHandler} like any other API error
 * (envelope with {@code meta.requestId}). OPTIONS requests (CORS preflight) are never counted.
 *
 * <p>The client is identified by {@link HttpServletRequest#getRemoteAddr()} only (D6); client-supplied headers are
 * never trusted here. Behind a reverse proxy the server's forwarding configuration decides what that address is.
 */
public class RateLimitInterceptor implements HandlerInterceptor {
    private final RateLimitProperties properties;
    private final RateLimiter localLimiter;
    private final RateLimiter sharedLimiter;

    /**
     * @param localLimiter  instance memory limiter for {@link RateLimitGroup#STATIONS}
     * @param sharedLimiter limiter for {@link RateLimitGroup#PLACES} and {@link RateLimitGroup#ROUTES}
     *                      (Redis on the infra profile, the local one otherwise)
     */
    public RateLimitInterceptor(RateLimitProperties properties, RateLimiter localLimiter, RateLimiter sharedLimiter) {
        this.properties = properties;
        this.localLimiter = localLimiter;
        this.sharedLimiter = sharedLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!properties.enabled() || HttpMethod.OPTIONS.matches(request.getMethod())) return true;
        RateLimitGroup group = RateLimitGroup.classify(path(request, handler));
        if (group == null) return true;
        RateLimiter limiter = group == RateLimitGroup.STATIONS ? localLimiter : sharedLimiter;
        RateLimitDecision decision = limiter.tryAcquire(group.key() + ":" + clientHash(request.getRemoteAddr()),
                properties.policy(group));
        if (!decision.allowed()) throw new RateLimitExceededException(decision.retryAfterSeconds());
        return true;
    }

    /** The matched controller pattern when there is one, otherwise the raw path within the application. */
    static @Nullable String path(HttpServletRequest request, Object handler) {
        if (handler instanceof HandlerMethod) {
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (pattern instanceof PathPattern pathPattern) return pathPattern.getPatternString();
            if (pattern instanceof String text) return text;
        }
        String uri = request.getRequestURI();
        if (uri == null) return null;
        String contextPath = request.getContextPath();
        return contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length()) : uri;
    }

    /**
     * SHA-256 of the normalized client address (first 128 bits, hex), so raw IPs are not stored as Redis keys or
     * map keys. See {@link #normalizeClientAddress(String)} for the normalization.
     */
    static String clientHash(@Nullable String remoteAddr) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalizeClientAddress(remoteAddr).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }

    /**
     * Review M6: an IPv6 client usually owns a whole /64 and can rotate through it freely (privacy addresses), so
     * per-address buckets would not limit it. IPv6 addresses are therefore reduced to their /64 prefix, and an
     * IPv4-mapped IPv6 address ({@code ::ffff:a.b.c.d}) is treated as the IPv4 address. Anything that is not an IP
     * literal is used as is; this never performs a DNS lookup.
     */
    static String normalizeClientAddress(@Nullable String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isEmpty()) return "";
        String address = remoteAddr.strip();
        if (address.startsWith("[") && address.endsWith("]")) address = address.substring(1, address.length() - 1);
        int zone = address.indexOf('%');
        if (zone >= 0) address = address.substring(0, zone);
        // Only IP literals are parsed: InetAddress.getByName would resolve a host name.
        if (address.indexOf(':') < 0 || !IPV6_LITERAL.matcher(address).matches()) return address;
        InetAddress parsed;
        try {
            parsed = InetAddress.getByName(address);
        } catch (UnknownHostException | RuntimeException invalid) {
            return address;
        }
        if (parsed instanceof Inet4Address) return parsed.getHostAddress(); // IPv4-mapped IPv6
        byte[] bytes = parsed.getAddress();
        if (bytes.length != 16) return address;
        if (isIpv4Mapped(bytes)) {
            return (bytes[12] & 0xff) + "." + (bytes[13] & 0xff) + "." + (bytes[14] & 0xff) + "." + (bytes[15] & 0xff);
        }
        return "v6:" + HexFormat.of().formatHex(bytes, 0, 8) + "/64";
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) if (bytes[i] != 0) return false;
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9A-Fa-f:.]+");
}
