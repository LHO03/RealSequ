package com.docversion.web;

import com.docversion.service.ForbiddenOperationException;
import com.docversion.service.InvalidRequestException;
import com.docversion.service.ModificationBlockedException;
import com.docversion.service.ResourceNotFoundException;
import com.docversion.service.WorkflowConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 예외 → HTTP 상태코드 중앙 매핑 (P1: 400/404 분리).
 *
 * <p>기존에는 컨트롤러마다 인라인 try/catch로 IllegalArgumentException을 전부 404로 매핑해,
 * "승인자 0명"·"잘못된 mode" 같은 잘못된 입력까지 404가 나갔다. 이제 예외 타입으로 의미를 나눈다.
 * <ul>
 *   <li>{@link ResourceNotFoundException} → 404 (자원 없음)</li>
 *   <li>{@link InvalidRequestException} → 400 (잘못된 입력)</li>
 *   <li>{@link ForbiddenOperationException} → 403 (권한 없음)</li>
 *   <li>{@link WorkflowConflictException}, {@link ModificationBlockedException} → 409 (상태 충돌)</li>
 *   <li>그 밖의 {@link IllegalArgumentException} → 400 (예: 알 수 없는 상태 문자열)</li>
 * </ul>
 * <p>내부 정합성 이상(IllegalStateException 등)은 여기서 잡지 않고 프레임워크 기본 500으로 떨어진다.
 * 응답 형식은 인증 응답과 동일하게 {@code {"ok":false,"error":...}}로 통일한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(ResourceNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<Map<String, Object>> invalid(InvalidRequestException e) {
        return body(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler(ForbiddenOperationException.class)
    public ResponseEntity<Map<String, Object>> forbidden(ForbiddenOperationException e) {
        return body(HttpStatus.FORBIDDEN, e);
    }

    @ExceptionHandler({WorkflowConflictException.class, ModificationBlockedException.class})
    public ResponseEntity<Map<String, Object>> conflict(RuntimeException e) {
        return body(HttpStatus.CONFLICT, e);
    }

    /** DocumentStatus.of() 등이 던지는 원시 IllegalArgumentException(잘못된 입력)의 폴백 → 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badArgument(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e);
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        return ResponseEntity.status(status).body(Map.of("ok", false, "error", msg));
    }
}
