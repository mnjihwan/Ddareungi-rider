package com.ddareungi.global.resilience;

/**
 * A provider call stopped by the resilience layer rather than by the provider's own answer. Callers translate it into
 * their public error: {@link Kind#TIMEOUT} into {@code EXTERNAL_API_TIMEOUT} (504), the others into the provider's
 * 502 code. The message is internal and never contains provider bodies.
 */
public class ProviderCallException extends RuntimeException {
    public enum Kind {
        /** Read/connect timeout, or the request deadline ran out. */
        TIMEOUT,
        /** The provider's circuit breaker is open; no request was sent. */
        CIRCUIT_OPEN,
        /** Too many concurrent calls to this provider; no request was sent. */
        BULKHEAD_FULL
    }

    private final Kind kind;
    private final boolean budgetLimited;

    public ProviderCallException(Kind kind, String message) {
        this(kind, message, false);
    }

    /**
     * @param budgetLimited a {@link Kind#TIMEOUT} caused by the request's remaining deadline budget (smaller than
     *                      the provider read timeout, or already spent) rather than by the provider (review M7)
     */
    public ProviderCallException(Kind kind, String message, boolean budgetLimited) {
        super(message, null, false, false);
        this.kind = kind;
        this.budgetLimited = budgetLimited;
    }

    public Kind kind() { return kind; }

    public boolean budgetLimited() { return budgetLimited; }
}
