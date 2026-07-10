package com.gdav.bible.bible_references.exception;

/** Error al invocar un servicio externo. Se mapea a HTTP 502. */
public class ExternalServiceException extends AppException {
    public ExternalServiceException(String message) {
        super(ErrorCode.EXTERNAL_SERVICE_ERROR, message);
    }

    public ExternalServiceException(String message, Throwable cause) {
        super(ErrorCode.EXTERNAL_SERVICE_ERROR, message, cause);
    }
}