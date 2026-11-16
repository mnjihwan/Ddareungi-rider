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

    public ProviderCallException(Kind kind, String message) {
        super(message, null, false, false);
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
