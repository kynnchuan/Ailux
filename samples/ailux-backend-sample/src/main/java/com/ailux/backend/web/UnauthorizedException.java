package com.ailux.backend.web;

/**
 * Thrown when the request cannot be associated with a known user.
 * Mapped to HTTP 401 by {@link GlobalExceptionHandler}.
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
