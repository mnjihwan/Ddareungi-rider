package com.ddareungi.global.ratelimit;

import com.ddareungi.global.exception.ApiException;
import com.ddareungi.global.exception.ErrorCode;
import org.springframework.http.HttpHeaders;

/**
 * 429 {@code RATE_LIMIT_EXCEEDED} with a {@code Retry-After} header in seconds (spec 24). The existing
 * {@code GlobalExceptionHandler.handleApi} copies {@link #getHeaders()} into the failure envelope response.
 */
public class RateLimitExceededException extends ApiException {
    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        super(ErrorCode.RATE_LIMIT_EXCEEDED);
        if (retryAfterSeconds < 1) throw new IllegalArgumentException("retryAfterSeconds must be >= 1");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() { return retryAfterSeconds; }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        return headers;
    }
}
