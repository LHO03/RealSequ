package com.docversion;

import com.docversion.service.ForbiddenOperationException;
import com.docversion.service.InvalidRequestException;
import com.docversion.service.ModificationBlockedException;
import com.docversion.service.ResourceNotFoundException;
import com.docversion.service.WorkflowConflictException;
import com.docversion.web.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1: 예외 → HTTP 상태 매핑 단위 테스트 (Spring 컨텍스트/컨테이너 없이 순수 검증 — 빠름).
 * 핵심 회귀: "잘못된 입력"이 404가 아니라 400으로, "없음"은 404로 나가는지.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler("20MB");

    @Test
    void resourceNotFound_maps404() {
        ResponseEntity<Map<String, Object>> r = handler.notFound(
                new ResourceNotFoundException("문서를 찾을 수 없습니다: x"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).containsEntry("ok", false);
    }

    @Test
    void invalidRequest_maps400() {
        ResponseEntity<Map<String, Object>> r = handler.invalid(
                new InvalidRequestException("승인자를 1명 이상 지정해야 합니다."));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void forbidden_maps403() {
        ResponseEntity<Map<String, Object>> r = handler.forbidden(
                new ForbiddenOperationException("권한 없음"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void workflowConflict_and_modificationBlocked_map409() {
        assertThat(handler.conflict(new WorkflowConflictException("이미 열린 요청"))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(handler.conflict(new ModificationBlockedException("업로드 차단"))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void rawIllegalArgument_maps400() {
        // DocumentStatus.of() 등이 던지는 원시 IllegalArgumentException → 400 (과거엔 404였음)
        ResponseEntity<Map<String, Object>> r = handler.badArgument(
                new IllegalArgumentException("알 수 없는 문서 상태: FOO"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * 업로드 상한 초과는 413이어야 한다. 매핑이 없으면 프레임워크 기본 500이 나가,
     * 사용자는 파일이 커서 거부된 것인지 서버가 고장난 것인지 알 수 없었다.
     */
    @Test
    void maxUploadSizeExceeded_maps413_withLimitInMessage() {
        ResponseEntity<Map<String, Object>> r = handler.payloadTooLarge(
                new MaxUploadSizeExceededException(20L * 1024 * 1024));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(r.getBody()).containsEntry("ok", false);
        assertThat(String.valueOf(r.getBody().get("error")))
                .as("조치가 가능하도록 실제 상한을 함께 알려야 한다")
                .contains("20MB");
    }

    /** 상한 초과 외의 멀티파트 오류(본문 손상 등)는 잘못된 요청이므로 400. */
    @Test
    void otherMultipartError_maps400() {
        ResponseEntity<Map<String, Object>> r = handler.badMultipart(
                new MultipartException("본문을 해석할 수 없습니다"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
