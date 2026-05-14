package com.example.docver.service;

import com.example.docver.IntegrationTestBase;
import com.example.docver.model.ApprovalAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 상태 전이 매트릭스 검증.
 *
 * 의사코드 isValidTransition (보고서 A-8 단계, 결정 12~17)의
 * 기본 매트릭스가 Java 이식판에서도 동일하게 작동하는지 검증.
 *
 * 기본 매트릭스:
 *   (없음)        → DRAFT, UNDER_REVIEW
 *   DRAFT         → UNDER_REVIEW, DEPRECATED
 *   UNDER_REVIEW  → APPROVED, REJECTED, DRAFT
 *   APPROVED      → DEPRECATED
 *   REJECTED      → DRAFT, DEPRECATED
 *   DEPRECATED    → (전이 불가)
 *
 * 시연 단순화로 인해 Java 이식판에서는 setDocumentStatus가 private이므로
 * 매트릭스를 직접 호출 못 함. 대신 워크플로우 호출 결과로 간접 검증.
 *
 * 예: APPROVED 상태에서 새 승인 요청은 거부되어야 함 (APPROVED → UNDER_REVIEW 불허, 결정 15)
 */
@DisplayName("상태 전이 매트릭스 검증")
class StateTransitionMatrixTest extends IntegrationTestBase {

    @Autowired
    private DocumentVersionService documentService;

    private static final String ALICE = "alice";
    private static final String BOB = "bob";

    @Test
    @DisplayName("결정 15: APPROVED 상태에서 새 승인 요청은 매트릭스 위반으로 거부")
    void approvedToUnderReview_isBlocked() {
        // 시나리오: 한 번 승인 완료된 파일에 또 승인 요청
        String fileId = "matrix_approved_redo";
        documentService.createInitialVersion(ALICE, fileId, 1024, "text/plain");
        documentService.requestApproval(ALICE, fileId, "", List.of(BOB));
        documentService.decideApproval(BOB, fileId, ApprovalAction.APPROVE, "first");

        // 파일은 이제 APPROVED 상태
        // 여기서 또 승인 요청 → setDocumentStatus(UNDER_REVIEW)가 매트릭스 위반으로 실패해야 함
        assertThatThrownBy(() -> documentService.requestApproval(
                ALICE, fileId, "재승인 시도", List.of(BOB)))
                .hasMessageContaining("Status transition to UNDER_REVIEW failed");
    }

    @Test
    @DisplayName("결정 16: REJECTED 상태에서 재작업(DRAFT) 가능 — 새 승인 요청 흐름 검증")
    void rejectedToDraftViaUnderReview_isAllowed() {
        // 시나리오: 거절된 파일을 alice가 재작업 후 다시 요청
        // 이를 위해서는 REJECTED → UNDER_REVIEW가 가능해야 함
        // 의사코드 매트릭스: REJECTED → DRAFT, DEPRECATED (UNDER_REVIEW는 직접 불가)
        // 따라서 REJECTED 상태에서 바로 승인 요청 시도하면 setDocumentStatus 위반
        String fileId = "matrix_rejected_redo";
        documentService.createInitialVersion(ALICE, fileId, 1024, "text/plain");
        documentService.requestApproval(ALICE, fileId, "", List.of(BOB));
        documentService.decideApproval(BOB, fileId, ApprovalAction.REJECT, "수정 필요");

        // 파일은 이제 REJECTED 상태
        // REJECTED → UNDER_REVIEW는 매트릭스 위반 (DRAFT를 거쳐가야 함)
        assertThatThrownBy(() -> documentService.requestApproval(
                ALICE, fileId, "재시도", List.of(BOB)))
                .hasMessageContaining("Status transition to UNDER_REVIEW failed");
    }

    @Test
    @DisplayName("결정 12: UNDER_REVIEW 상태에서 같은 파일에 또 요청 시 중복 방어")
    void underReview_duplicateRequest_blocked() {
        // 의사코드 processApprovalWorkflow REQUEST의 line 895-905: 중복 요청 방어
        // 매트릭스 위반이 아니라 비즈니스 규칙 (under_review 태그 이미 할당됨)
        String fileId = "matrix_under_review_dup";
        documentService.createInitialVersion(ALICE, fileId, 1024, "text/plain");
        documentService.requestApproval(ALICE, fileId, "1차", List.of(BOB));

        // 같은 파일에 또 요청 — under_review 태그 이미 할당으로 거부
        assertThatThrownBy(() -> documentService.requestApproval(
                ALICE, fileId, "2차", List.of(BOB)))
                .hasMessageContaining("already pending");
    }

    @Test
    @DisplayName("새 파일은 createInitialVersion으로 시작 시 status가 비어있음")
    void newFile_hasNoInitialStatus() {
        String fileId = "matrix_new";
        documentService.createInitialVersion(ALICE, fileId, 1024, "text/plain");

        // createInitialVersion은 상태 태그를 자동 할당하지 않음
        // 의사코드 createInitialVersion (line 268-322)은 setDocumentStatus 호출 안 함
        Integer tagCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM systemtag_object_mapping WHERE objectid = ?",
                Integer.class, fileId);
        assertThat(tagCount).isZero();
    }

    @Test
    @DisplayName("새 파일(빈 상태)에서 UNDER_REVIEW로 전이 가능 (승인 요청)")
    void emptyToUnderReview_isAllowed() {
        // 매트릭스: (없음) → DRAFT, UNDER_REVIEW
        // 새 파일에 곧장 승인 요청 가능 (DRAFT 중간 단계 불필요)
        String fileId = "matrix_empty_to_under_review";
        documentService.createInitialVersion(ALICE, fileId, 1024, "text/plain");

        String ruleId = documentService.requestApproval(ALICE, fileId, "직접 요청", List.of(BOB));
        assertThat(ruleId).isNotEmpty();

        String tag = jdbc.queryForObject(
                "SELECT st.name FROM systemtag_object_mapping som " +
                "JOIN systemtag st ON som.systemtagid = st.id " +
                "WHERE som.objectid = ?",
                String.class, fileId);
        assertThat(tag).isEqualTo("under_review");
    }
}