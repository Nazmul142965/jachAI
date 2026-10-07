package com.jachai.shared.error;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import java.util.TreeMap;
import java.util.StringJoiner;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> apiError(ApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ApiError(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException exception) {
        Map<String, String> details = new TreeMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                details.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR", "Request validation failed", details));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> invalidParameter(ConstraintViolationException exception) {
        Map<String, String> details = new TreeMap<>();
        exception.getConstraintViolations().forEach(violation -> {
            String field = null;
            for (var node : violation.getPropertyPath()) field = node.getName();
            if (safeField(field)) details.putIfAbsent(field, "Invalid value");
        });
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR", "Request validation failed", details));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> invalidMethodParameter(HandlerMethodValidationException exception) {
        Map<String, String> details = new TreeMap<>();
        exception.getAllValidationResults().forEach(result -> {
            String field = result.getMethodParameter().getParameterName();
            if (safeField(field)) details.putIfAbsent(field, "Invalid value");
        });
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR", "Request validation failed", details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> malformedBody(HttpMessageNotReadableException exception) {
        Map<String, String> details = new TreeMap<>();
        Throwable cause = exception.getCause();
        while (cause != null) {
            if (cause instanceof JsonMappingException mapping) {
                String field = null;
                for (var reference : mapping.getPath()) {
                    if (reference.getFieldName() != null) field = reference.getFieldName();
                }
                if (safeField(field)) {
                    details.put(field, cause instanceof InvalidFormatException format
                            ? permittedEnumValues(format.getTargetType()) : "Invalid value");
                }
                break;
            }
            cause = cause.getCause();
        }
        return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST", "Invalid request", details));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> malformedParameter(MethodArgumentTypeMismatchException exception) {
        Map<String, String> details = new TreeMap<>();
        if (safeField(exception.getName())) {
            details.put(exception.getName(), permittedEnumValues(exception.getRequiredType()));
        }
        return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST", "Invalid request", details));
    }

    private static boolean safeField(String field) {
        return field != null && field.matches("[A-Za-z_][A-Za-z0-9_]{0,63}");
    }

    private static String permittedEnumValues(Class<?> targetType) {
        if (targetType == null || !targetType.isEnum() || targetType.getEnumConstants().length > 20) {
            return "Invalid value";
        }
        StringJoiner choices = new StringJoiner(", ", "Choose one of: ", "");
        for (Object value : targetType.getEnumConstants()) choices.add(((Enum<?>) value).name());
        return choices.toString();
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> notFound(NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("NOT_FOUND", "Resource not found"));
    }
}
