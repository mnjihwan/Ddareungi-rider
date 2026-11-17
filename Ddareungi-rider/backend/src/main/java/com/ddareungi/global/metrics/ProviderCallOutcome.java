package com.ddareungi.global.metrics;

import com.ddareungi.global.resilience.ProviderCallException;
import org.jspecify.annotations.Nullable;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;

/**
 * The {@code outcome} tag of {@code ddareungi.provider.calls} (phase 3 O8). A fixed, small set so the tag never
 * carries request data.
 */
public enum ProviderCallOutcome {
    SUCCESS("success"),
    /** Read/connect timeout or the request deadline ran out. */
    TIMEOUT("timeout"),
    /** 5xx answers, connection failures and any other provider-side error. */
    SERVER_ERROR("server_error"),
    /** 4xx answers including 429 (quota, credentials, bad input). */
    CLIENT_ERROR("client_error"),
    /** Rejected by the open circuit breaker; nothing was sent. */
    CIRCUIT_OPEN("circuit_open"),
    /** Rejected by the full bulkhead; nothing was sent. */
    BULKHEAD_FULL("bulkhead_full");

    private final String tag;

    ProviderCallOutcome(String tag) { this.tag = tag; }

    public String tag() { return tag; }

    /** Outcome of a call that ended with {@code error}; null means it succeeded. */
    public static ProviderCallOutcome of(@Nullable Throwable error) {
        if (error == null) return SUCCESS;
        if (error instanceof ProviderCallException call) {
            return switch (call.kind()) {
                case TIMEOUT -> TIMEOUT;
                case CIRCUIT_OPEN -> CIRCUIT_OPEN;
                case BULKHEAD_FULL -> BULKHEAD_FULL;
            };
        }
        if (error instanceof RestClientResponseException response) {
            return response.getStatusCode().is4xxClientError() ? CLIENT_ERROR : SERVER_ERROR;
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) return TIMEOUT;
            if (cause.getCause() == cause) break;
        }
        return SERVER_ERROR;
    }
}
