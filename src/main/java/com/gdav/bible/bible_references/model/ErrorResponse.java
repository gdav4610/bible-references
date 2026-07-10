package com.gdav.bible.bible_references.model;

import java.time.Instant;
import java.util.List;

/**
 * Estructura de error consistente devuelta por el {@code GlobalExceptionHandler}.
 * No expone stack traces ni detalles internos al cliente.
 */
public record ErrorResponse(
        String errorCode,
        String message,
        String path,
        Instant timestamp,
        List<FieldError> fieldErrors
) {
    public record FieldError(String field, String message) {}
}