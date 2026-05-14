package com.example.docver.exception;

/**
 * 워크플로우 비즈니스 로직 예외.
 * REST 컨트롤러에서 400 Bad Request로 매핑됨 (ExceptionHandler).
 */
public class WorkflowException extends RuntimeException {
    public WorkflowException(String message) {
        super(message);
    }
}
