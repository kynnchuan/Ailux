package com.ailux.backend.web;

/**
 * Thrown when the authenticated user is not allowed to use the resolved model.
 * Mapped to HTTP 403 + {@code {"error":"model_not_available"}} by {@link GlobalExceptionHandler}.
 */
public class ModelAccessException extends RuntimeException {

    private final String model;

    public ModelAccessException(String model) {
        super("You don't have access to model: " + model);
        this.model = model;
    }

    public String getModel() {
        return model;
    }
}
