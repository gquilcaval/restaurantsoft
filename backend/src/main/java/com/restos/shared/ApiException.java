package com.restos.shared;

import org.springframework.http.HttpStatus;

/**
 * Excepción de negocio que conserva un código HTTP y un mensaje seguro para el cliente.
 * code (opcional): identifica un caso que la pantalla resuelve de forma especial (por ejemplo, SAME_NAME).
 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public ApiException(HttpStatus status, String message, String code) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
