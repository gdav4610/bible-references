package com.gdav.bible.bible_references.controller;

import com.gdav.bible.bible_references.exception.BusinessRuleException;
import com.gdav.bible.bible_references.exception.ErrorCode;
import com.gdav.bible.bible_references.exception.ExternalServiceException;
import com.gdav.bible.bible_references.exception.ResourceNotFoundException;
import com.gdav.bible.bible_references.model.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Manejador global de excepciones. Único punto de traducción de excepciones a respuestas HTTP.
 * Devuelve siempre {@link ErrorResponse} y nunca expone stack traces al cliente.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                       HttpServletRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldError)
                .toList();
        logger.warn("Validation failed on {}: {} field error(s)", request.getRequestURI(), fieldErrors.size());
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                "Validation failed for one or more fields", request, fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
                                                                    HttpServletRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = ex.getConstraintViolations().stream()
                .map(this::toFieldError)
                .toList();
        logger.warn("Constraint violation on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                "Validation failed for one or more parameters", request, fieldErrors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidation(HandlerMethodValidationException ex,
                                                                        HttpServletRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String field = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error ->
                    fieldErrors.add(new ErrorResponse.FieldError(field, error.getDefaultMessage())));
        });
        logger.warn("Parameter validation failed on {}: {} error(s)", request.getRequestURI(), fieldErrors.size());
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                "Validation failed for one or more parameters", request, fieldErrors);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex,
                                                                 HttpServletRequest request) {
        logger.warn("Missing request parameter on {}: {}", request.getRequestURI(), ex.getParameterName());
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                "Required parameter is missing", request,
                List.of(new ErrorResponse.FieldError(ex.getParameterName(), "required parameter is missing")));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest request) {
        logger.warn("Type mismatch on {}: parameter {}", request.getRequestURI(), ex.getName());
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                "Parameter has an invalid value", request,
                List.of(new ErrorResponse.FieldError(ex.getName(), "invalid value")));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        logger.warn("Resource not found on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getErrorCode(), ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ErrorResponse> handleBusinessRule(BusinessRuleException ex, HttpServletRequest request) {
        logger.warn("Business rule violation on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getErrorCode(), ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(ExternalServiceException.class)
    public ResponseEntity<ErrorResponse> handleExternalService(ExternalServiceException ex, HttpServletRequest request) {
        logger.error("External service error on {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return build(HttpStatus.BAD_GATEWAY, ex.getErrorCode(),
                "An upstream service is currently unavailable", request, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex, HttpServletRequest request) {
        logger.error("Unhandled error on {}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred", request, List.of());
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, ErrorCode errorCode, String message,
                                                HttpServletRequest request, List<ErrorResponse.FieldError> fieldErrors) {
        ErrorResponse body = new ErrorResponse(
                errorCode.name(),
                message,
                request.getRequestURI(),
                Instant.now(),
                fieldErrors
        );
        return ResponseEntity.status(status).body(body);
    }

    private ErrorResponse.FieldError toFieldError(FieldError fieldError) {
        return new ErrorResponse.FieldError(fieldError.getField(), fieldError.getDefaultMessage());
    }

    private ErrorResponse.FieldError toFieldError(ConstraintViolation<?> violation) {
        String field = violation.getPropertyPath() == null ? "" : violation.getPropertyPath().toString();
        return new ErrorResponse.FieldError(field, violation.getMessage());
    }
}