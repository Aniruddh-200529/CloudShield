package com.cloudshield.backend.api;

import com.cloudshield.backend.service.ConflictException;
import com.cloudshield.backend.service.NotFoundException;
import com.cloudshield.backend.service.MfaConfigurationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> notFound(NotFoundException ex, HttpServletRequest request) { return response(HttpStatus.NOT_FOUND, ex.getMessage(), request, Map.of()); }
    @ExceptionHandler(MfaConfigurationException.class)
    ResponseEntity<ApiError> mfaConfiguration(MfaConfigurationException ex, HttpServletRequest request) { return response(HttpStatus.SERVICE_UNAVAILABLE, "MFA is unavailable because its encryption configuration is missing or invalid", request, Map.of()); }
    @ExceptionHandler({ConflictException.class, DataIntegrityViolationException.class})
    ResponseEntity<ApiError> conflict(RuntimeException ex, HttpServletRequest request) { return response(HttpStatus.CONFLICT, ex instanceof ConflictException ? ex.getMessage() : "The request conflicts with existing data or a related record", request, Map.of()); }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> badRequest(IllegalArgumentException ex, HttpServletRequest request) { return response(HttpStatus.BAD_REQUEST, ex.getMessage(), request, Map.of()); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        return response(HttpStatus.BAD_REQUEST, "Request validation failed", request, fields);
    }
    @ExceptionHandler({ConstraintViolationException.class, MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> invalidParameter(Exception ex, HttpServletRequest request) { return response(HttpStatus.BAD_REQUEST, "Request parameters or body are invalid", request, Map.of()); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest request) { return response(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected server error occurred", request, Map.of()); }
    private ResponseEntity<ApiError> response(HttpStatus status, String message, HttpServletRequest request, Map<String, String> fields) {
        return ResponseEntity.status(status).body(new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, request.getRequestURI(), fields));
    }
}
