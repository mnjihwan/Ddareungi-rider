package com.ddareungi.routing;

import com.ddareungi.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Routing failure with a stable {@link #code}.
 *
 * <p>The HTTP response is decided by {@code GlobalExceptionHandler}: a code equal to an {@link ErrorCode} name is
 * used as is, a legacy routing code (for example {@code NO_RENTAL_STATION}) is mapped to its public code, and an
 * unknown code becomes 500. {@link #status} is therefore informational; for domain codes it is kept equal to the
 * status of the public code so that the code reads the same as the response. New code should use
 * {@link #RouteException(ErrorCode, String)}.
 */
public class RouteException extends RuntimeException {
    public final HttpStatus status;
    public final String code;
    public RouteException(HttpStatus status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    /** A public code (spec 10): the response uses exactly this code and its status. */
    public RouteException(ErrorCode code, String message) {
        this(code.status(), code.name(), message);
    }
}
