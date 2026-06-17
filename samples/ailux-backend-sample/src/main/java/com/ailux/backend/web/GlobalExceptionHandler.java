package com.ailux.backend.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Maps business exceptions to proper HTTP status codes + a stable JSON error body.
 *
 * <p>This is what lets the chat endpoint reject a disallowed model with a real
 * HTTP 403 (so the SDK's ErrorMapper can map by status) instead of degrading to
 * an in-stream SSE error event — the validation runs in the controller before the
 * SseEmitter is returned.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ModelAccessException.class)
    public ResponseEntity<Map<String, String>> handleModelAccess(ModelAccessException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", "model_not_available",
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, String>> handleUnauthorized(UnauthorizedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "error", "unauthorized",
                "message", ex.getMessage()
        ));
    }
}
