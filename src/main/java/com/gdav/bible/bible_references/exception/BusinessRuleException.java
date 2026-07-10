package com.gdav.bible.bible_references.exception;

/** Violación de una regla de negocio del dominio. Se mapea a HTTP 422. */
public class BusinessRuleException extends AppException {
    public BusinessRuleException(String message) {
        super(ErrorCode.BUSINESS_RULE_VIOLATION, message);
    }
}