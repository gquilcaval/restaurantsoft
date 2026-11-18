package com.restos.shared;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Convierte errores internos en respuestas consistentes sin filtrar detalles de implementación. */
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> handleApiException(ApiException exception) {
        var body = new java.util.LinkedHashMap<String, Object>(Map.of(
                "timestamp", Instant.now(),
                "status", exception.getStatus().value(),
                "message", exception.getMessage()));
        if (exception.getCode() != null) body.put("code", exception.getCode());
        return ResponseEntity.status(exception.getStatus()).body(body);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Map<String, Object>> handleMethodValidation(HandlerMethodValidationException exception) {
        var firstError = exception.getAllErrors().stream().findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("La solicitud contiene datos inválidos");
        return ResponseEntity.badRequest().body(Map.of(
                "timestamp", Instant.now(), "status", 400, "message", firstError));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, Object>> handleUploadSize(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(413).body(Map.of(
                "timestamp", Instant.now(), "status", 413, "message", "La imagen supera el tamaño máximo de 5 MB"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception) {
        var firstError = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("La solicitud contiene datos inválidos");
        return ResponseEntity.badRequest().body(Map.of(
                "timestamp", Instant.now(), "status", 400, "message", firstError));
    }
}
