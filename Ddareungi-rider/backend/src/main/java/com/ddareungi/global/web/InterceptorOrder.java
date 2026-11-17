package com.ddareungi.global.web;

/**
 * Explicit order of the API handler interceptors (review L11). Lower runs first; each registration sets its order
 * from here instead of relying on the default order 0 and on the order in which configurers happen to be applied.
 * <ol>
 *   <li>{@link #DEPRECATION}: adds {@code Deprecation}/{@code Sunset} first, so every later error keeps them.</li>
 *   <li>{@link #MAINTENANCE}: 503 {@code MAINTENANCE_MODE} before anything is counted or read.</li>
 *   <li>{@link #REQUEST_BODY_LIMIT}: 413 for a declared {@code Content-Length} over 16 KiB, before rate limiting.</li>
 *   <li>{@link #RATE_LIMIT}: the token buckets, last, so requests refused above never consume a token.</li>
 * </ol>
 * Verified on the real {@code HandlerExecutionChain} by {@code InterceptorOrderTest}.
 */
public final class InterceptorOrder {
    public static final int DEPRECATION = -200;
    public static final int MAINTENANCE = -100;
    public static final int REQUEST_BODY_LIMIT = -50;
    public static final int RATE_LIMIT = 0;

    private InterceptorOrder() {}
}
