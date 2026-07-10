package com.gdav.bible.bible_references.exception;

/**
 * Excepción base de la aplicación. Toda excepción de dominio debe derivar de esta clase
 * y aportar un {@link ErrorCode} que permita identificar el tipo de error de forma programática.
 */
public abstract class AppException extends RuntimeException {

    private final ErrorCode errorCode;

    protected AppException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected AppException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}