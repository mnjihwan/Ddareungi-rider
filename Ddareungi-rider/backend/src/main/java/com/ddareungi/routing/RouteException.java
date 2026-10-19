package com.ddareungi.routing;

import org.springframework.http.HttpStatus;

public class RouteException extends RuntimeException {
    public final HttpStatus status;
    public final String code;
    public RouteException(HttpStatus status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
}
