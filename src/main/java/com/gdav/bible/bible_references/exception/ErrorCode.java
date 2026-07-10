package com.gdav.bible.bible_references.exception;

/**
 * Códigos de error de dominio para identificar programáticamente el tipo de error.
 * Se exponen en {@code ErrorResponse.errorCode}.
 */
public enum ErrorCode {
    VALIDATION_ERROR,
    RESOURCE_NOT_FOUND,
    BUSINESS_RULE_VIOLATION,
    CONFLICT,
    EXTERNAL_SERVICE_ERROR,
    INTERNAL_ERROR
}