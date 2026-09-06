package com.docversion.web;

import com.docversion.service.ForbiddenOperationException;
import com.docversion.service.InvalidRequestException;
import com.docversion.service.ModificationBlockedException;
import com.docversion.service.ResourceNotFoundException;
import com.docversion.service.WorkflowConflictException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

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
 *   <li>{@link MaxUploadSizeExceededException} → 413 (업로드 상한 초과)</li>
 *   <li>{@link MultipartException} → 400 (잘못된 멀티파트 요청)</li>
 *   <li>그 밖의 {@link IllegalArgumentException} → 400 (예: 알 수 없는 상태 문자열)</li>
 * </ul>
 * <p>내부 정합성 이상(IllegalStateException 등)은 여기서 잡지 않고 프레임워크 기본 500으로 떨어진다.
 * 응답 형식은 인증 응답과 동일하게 {@code {"ok":false,"error":...}}로 통일한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 413 응답에 실을 상한 표기. 실제 상한은 {@code spring.servlet.multipart.max-file-size}가
     * 결정하며 이 값은 안내 문구용이다. 두 값은 application.yml에서 같은 환경변수를 참조한다.
     *
     * <p>예외 객체의 {@code getMaxUploadSize()}를 쓰지 않는 이유: 톰캣 경로에서는 이 값이
     * -1로 넘어오는 경우가 있어 "최대 -1바이트" 같은 무의미한 문구가 나간다.
     */
    private final String maxFileSizeLabel;

    /**
     * 생성자는 하나만 둔다. 편의용 기본 생성자를 함께 두면 스프링이 인자 없는 쪽을 골라
     * 설정값이 조용히 무시된다(주입 실패가 아니라 기본값으로 동작해서 알아채기 어렵다).
     */
    public GlobalExceptionHandler(
            @Value("${docversion.upload.max-file-size-label:20MB}") String maxFileSizeLabel) {
        this.maxFileSizeLabel = maxFileSizeLabel;
    }

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

    /**
     * 업로드 상한 초과 → 413. (RD-SRS-9.1 · 9.2)
     *
     * <p>이 매핑이 없으면 프레임워크 기본 500이 나가, 사용자는 파일이 커서 거부된 것인지
     * 서버가 고장난 것인지 구분할 수 없다. 상한값을 함께 실어 보내 조치가 가능하게 한다.
     *
     * <p>{@link MaxUploadSizeExceededException}은 컨트롤러 진입 전 멀티파트 해석 단계에서
     * 발생하지만, 스프링이 예외 처리 경로로 넘겨 주므로 이 어드바이스가 받는다.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> payloadTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("ok", false,
                        "error", "파일이 너무 큽니다. 업로드 가능한 최대 크기는 "
                                + maxFileSizeLabel + "입니다."));
    }

    /**
     * 그 밖의 멀티파트 오류(본문 손상, 경계 문자열 이상 등) → 400.
     *
     * <p>상한 초과보다 상위 타입이므로, 위의 413 매핑이 먼저 적용된 뒤 나머지가 여기로 온다.
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, Object>> badMultipart(MultipartException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("ok", false, "error", "업로드 요청을 해석할 수 없습니다."));
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
