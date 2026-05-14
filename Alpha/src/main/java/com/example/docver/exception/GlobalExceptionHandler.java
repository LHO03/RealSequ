package com.example.docver.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * REST API 전역 예외 처리.
 * 시연 데모용 — 운영 코드라면 더 세분화 필요.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(WorkflowException.class)
    public ResponseEntity<Map<String, String>> handleWorkflow(WorkflowException ex) {
        log.warn("Workflow rejected: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "workflow", "message", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        StringBuilder sb = new StringBuilder();
        ex.getBindingResult().getFieldErrors().forEach(err ->
                sb.append(err.getField()).append(": ").append(err.getDefaultMessage()).append("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "validation", "message", sb.toString()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "internal", "message", ex.getMessage()));
    }
}
