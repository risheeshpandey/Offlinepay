package com.risheesh.offlinepay.common;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    public record ErrorResponse(String code, String message, Instant timestamp) {}

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> business(ApiException ex) {
        log.warn("request_rejected code={} status={}", ex.code(), ex.status().value());
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler({jakarta.validation.ConstraintViolationException.class, MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class})
    ResponseEntity<ErrorResponse> validation(Exception ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", "Check request fields and headers", Instant.now()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorResponse> database(DataAccessException ex) {
        log.error("database_operation_failed type={}", ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("DATABASE_UNAVAILABLE", "Retry with the same idempotency key", Instant.now()));
    }
}
