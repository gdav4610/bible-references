package com.gdav.bible.bible_references.exception;

/** Recurso no encontrado. Se mapea a HTTP 404. */
public class ResourceNotFoundException extends AppException {
    public ResourceNotFoundException(String message) {
        super(ErrorCode.RESOURCE_NOT_FOUND, message);
    }
}