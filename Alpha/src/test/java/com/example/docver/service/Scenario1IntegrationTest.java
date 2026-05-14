package com.example.docver.service;

import com.example.docver.IntegrationTestBase;
import com.example.docver.model.ApprovalAction;
import com.example.docver.model.NotificationInfo;
import com.example.docver.model.VersionInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 시나리오 1 — 문서 라이프사이클 전체 흐름 통합 테스트.
 *
 * 시연 8단계 (Postman 컬렉션과 동일 순서) 전부 검증:
 *   1. alice가 초기 버전 생성
 *   2. alice가 문서 수정
 *   3. alice가 본인 알림 확인 (version_created + version_updated)
 *   4. alice가 bob에게 승인 요청
 *   5. bob이 알림 확인 (approval_requested)
 *   6. bob의 안 읽은 알림 카운트
 *   7. bob이 승인 결정
 *   8. alice가 최종 결과 알림 확인 (approval_completed)
 *
 * 추가로 회의 시연에서 발생 가능한 에러 케이스도 검증:
 *   - 권한 없는 사용자의 승인 시도
 *   - 같은 사용자의 재결정 시도
 *   - 잘못된 상태 전이
 *   - 중복 승인 요청
 */
@DisplayName("Scenario 1: 문서 라이프사이클 전체 흐름")
class Scenario1IntegrationTest extends IntegrationTestBase {

    @Autowired
    private DocumentVersionService documentService;

    @Autowired
    private NotificationService notificationService;

    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final String CAROL = "carol";

    // ============================================================
    // 정상 흐름 — 시연에서 그대로 동작해야 함
    // ============================================================

    @Test
    @DisplayName("정상: alice 업로드 → 수정 → 승인 요청 → bob 승인 → alice 알림 확인")
    void happyPath_fullScenario() {
        String fileId = "demo_file_001";

        // 1. alice 초기 버전 생성
        VersionInfo v1 = documentService.createInitialVersion(ALICE, fileId, 1024, "text/plain");
        assertThat(v1.getVersionId()).startsWith(fileId + ".v");
        assertThat(v1.getUserId()).isEqualTo(ALICE);
        assertThat(v1.getSize()).isEqualTo(1024);

        // 2. alice 문서 수정
        VersionInfo v2 = documentService.updateDocument(ALICE, fileId, 2048, "text/plain");
        assertThat(v2.getVersionId()).isNotEqualTo(v1.getVersionId());
        assertThat(v2.getSize()).isEqualTo(2048);

        // 3. alice 본인 알림 (version_created + version_updated)
        //    의사코드 ③단계 매트릭스: version_created→소유자, version_updated→소유자+마지막 수정자
        //    alice는 둘 다 본인이므로 양쪽 모두 수신
        List<NotificationInfo> aliceNotifs = notificationService.getUserNotifications(ALICE, false, 20, 0);
        assertThat(aliceNotifs).hasSize(2);
        assertThat(aliceNotifs).extracting(NotificationInfo::getSubject)
                .containsExactlyInAnyOrder("version_created", "version_updated");

        // 4. alice가 bob에게 승인 요청
        String ruleId = documentService.requestApproval(ALICE, fileId,
                "검토 부탁드립니다", List.of(BOB));
        assertThat(ruleId).startsWith("rule_");

        // DB 상태 검증: 파일이 under_review로 전이됨
        String currentTag = jdbc.queryForObject(
                "SELECT st.name FROM systemtag_object_mapping som " +
                "JOIN systemtag st ON som.systemtagid = st.id " +
                "WHERE som.objectid = ? AND som.objecttype = 'files'",
                String.class, fileId);
        assertThat(currentTag).isEqualTo("under_review");

        // 5. bob 알림 확인 (approval_requested)
        List<NotificationInfo> bobNotifs = notificationService.getUserNotifications(BOB, true, 20, 0);
        assertThat(bobNotifs).hasSize(1);
        assertThat(bobNotifs.get(0).getSubject()).isEqualTo("approval_requested");
        assertThat(bobNotifs.get(0).getReadAt()).isNull();  // 안 읽음 상태

        // 6. bob 안 읽은 알림 카운트
        long unread = notificationService.getUnreadCount(BOB);
        assertThat(unread).isEqualTo(1);

        // 7. bob 승인 결정
        boolean approved = documentService.decideApproval(BOB, fileId,
                ApprovalAction.APPROVE, "검토 완료, 승인합니다");
        assertThat(approved).isTrue();

        // DB 상태 검증: 파일이 approved로 전이, 규칙은 CLOSED
        String finalTag = jdbc.queryForObject(
                "SELECT st.name FROM systemtag_object_mapping som " +
                "JOIN systemtag st ON som.systemtagid = st.id " +
                "WHERE som.objectid = ? AND som.objecttype = 'files'",
                String.class, fileId);
        assertThat(finalTag).isEqualTo("approved");

        String ruleStatus = jdbc.queryForObject(
                "SELECT status FROM approval_rules WHERE id = ?",
                String.class, ruleId);
        assertThat(ruleStatus).isEqualTo("CLOSED");

        // 8. alice 최종 알림 (approval_completed)
        List<NotificationInfo> aliceFinal = notificationService.getUserNotifications(ALICE, true, 20, 0);
        // 기존 안 읽은 알림: version_created, version_updated, + approval_completed
        assertThat(aliceFinal).extracting(NotificationInfo::getSubject)
                .contains("approval_completed");

        NotificationInfo completedNotif = aliceFinal.stream()
                .filter(n -> "approval_completed".equals(n.getSubject()))
                .findFirst()
                .orElseThrow();
        assertThat(completedNotif.getMessage()).contains("approved");
        assertThat(completedNotif.getMessage()).contains(BOB);
    }

    @Test
    @DisplayName("정상: REJECT 흐름도 동일하게 동작")
    void happyPath_reject() {
        String fileId = "demo_file_reject";

        documentService.createInitialVersion(ALICE, fileId, 1024, "text/plain");
        documentService.requestApproval(ALICE, fileId, "검토 부탁", List.of(BOB));

        documentService.decideApproval(BOB, fileId, ApprovalAction.REJECT, "수정 필요");

        // 파일은 rejected 상태
        String tag = jdbc.queryForObject(
                "SELECT st.name FROM systemtag_object_mapping som " +
                "JOIN systemtag st ON som.systemtagid = st.id " +
                "WHERE som.objectid = ? AND som.objecttype = 'files'",
                String.class, fileId);
        assertThat(tag).isEqualTo("rejected");

        // alice는 거절 알림 받음 (의사코드 #4 수정사항: notifyStakeholders 경유 일관성)
        List<NotificationInfo> aliceNotifs = notificationService.getUserNotifications(ALICE, false, 20, 0);
        assertThat(aliceNotifs).extracting(NotificationInfo::getMessage)
                .anyMatch(msg -> msg.contains("rejected"));
    }

    // ============================================================
    // 의사코드 검토(05/14) 수정 사항 검증
    // 검토에서 잡은 버그가 실제로 수정되었는지 회귀 테스트
    // ============================================================

    @Nested
    @DisplayName("의사코드 검토 회귀 검증")
    class ReviewRegressionTests {

        @Test
        @DisplayName("[검토 #5] approval_completed broadcast에서 요청자가 알림 받음")
        void completedBroadcastReachesRequester() {
            // 의사코드 검토 #5에서 잡은 버그:
            //   processApprovalDecision의 broadcast 시점에는 rule이 CLOSED 상태인데
            //   getDefaultStakeholders가 OPEN rule만 조회해서 빈 결과 반환 → alice 알림 누락
            // Java 이식판: requesterId를 명시 targets로 전달하므로 이 버그는 구조적으로 회피됨
            // 본 테스트는 그 동작이 유지되는지 회귀 검증
            String fileId = "regression_5";
            documentService.createInitialVersion(ALICE, fileId, 1024, "text/plain");
            documentService.requestApproval(ALICE, fileId, "", List.of(BOB));
            documentService.decideApproval(BOB, fileId, ApprovalAction.APPROVE, "OK");

            List<NotificationInfo> aliceNotifs = notificationService.getUserNotifications(ALICE, false, 20, 0);
            boolean hasCompleted = aliceNotifs.stream()
                    .anyMatch(n -> "approval_completed".equals(n.getSubject()));
            assertThat(hasCompleted)
                    .as("요청자(alice)가 approval_completed 알림을 받아야 함")
                    .isTrue();
        }

        @Test
        @DisplayName("[검토 #3] 중복 승인자 입력 시에도 PK 충돌 없이 정상 등록")
        void duplicateApproversAreDeduplicated() {
            // 의사코드 검토 #3에서 잡은 버그:
            //   approvers에 같은 ID 두 번 들어가면 PK 충돌로 부분 INSERT
            // Java 이식판: insertApprover에 INSERT IGNORE 사용 → silent skip
            String fileId = "regression_3";
            documentService.createInitialVersion(ALICE, fileId, 1024, "text/plain");

            // bob을 두 번 넣어도 예외 없이 등록되어야 함
            String ruleId = documentService.requestApproval(ALICE, fileId,
                    "dup test", List.of(BOB, BOB));

            Integer approverCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM approval_rule_approvers WHERE rule_id = ?",
                    Integer.class, ruleId);
            assertThat(approverCount).isEqualTo(1);  // dedup으로 1명만 등록됨
        }

        @Test
        @DisplayName("[검토 #1] onDocumentModified는 version_updated 알림을 발송")
        void updateDocumentEmitsVersionUpdatedEvent() {
            // 의사코드 검토 #1: dispatchEvent("version_created") → "version_updated" 수정
            // Java 이식판은 notificationService.notifyStakeholders로 "version_updated" 발송
            String fileId = "regression_1";
            documentService.createInitialVersion(ALICE, fileId, 1024, "text/plain");
            documentService.updateDocument(ALICE, fileId, 2048, "text/plain");

            List<NotificationInfo> notifs = notificationService.getUserNotifications(ALICE, false, 20, 0);
            List<String> subjects = notifs.stream().map(NotificationInfo::getSubject).toList();

            assertThat(subjects).contains("version_created", "version_updated");
            // 두 이벤트가 모두 분리되어 발송 (의미 구분 가능)
            long createdCount = subjects.stream().filter("version_created"::equals).count();
            long updatedCount = subjects.stream().filter("version_updated"::equals).count();
            assertThat(createdCount).isEqualTo(1);
            assertThat(updatedCount).isEqualTo(1);
        }
    }

    // ============================================================
    // 에러 케이스 — 시연 중 박사님이 던질 수 있는 질문에 대응
    // ============================================================

    @Nested
    @DisplayName("에러 케이스")
    class ErrorCases {

        @Test
        @DisplayName("권한 없는 사용자(carol)는 승인 결정 불가")
        void unauthorizedUserCannotDecide() {
            String fileId = "err_unauth";
            documentService.createInitialVersion(ALICE, fileId, 1024, "text/plain");
            documentService.requestApproval(ALICE, fileId, "", List.of(BOB));

            // carol은 승인자가 아님
            assertThatThrownBy(() -> documentService.decideApproval(
                    CAROL, fileId, ApprovalAction.APPROVE, "권한 없음"))
                    .hasMessageContaining("not an approver");
        }

        @Test
        @DisplayName("같은 승인자의 재결정은 차단됨")
        void duplicateDecisionIsBlocked() {
            String fileId = "err_dup_decide";
            documentService.createInitialVersion(ALICE, fileId, 1024, "text/plain");
            documentService.requestApproval(ALICE, fileId, "", List.of(BOB));
            documentService.decideApproval(BOB, fileId, ApprovalAction.APPROVE, "first");

            // bob이 다시 결정 시도 — 차단되어야 함
            assertThatThrownBy(() -> documentService.decideApproval(
                    BOB, fileId, ApprovalAction.REJECT, "마음 바뀜"))
                    .hasMessageContaining("already");  // "already made a decision" 또는 "already" 포함
        }

        @Test
        @DisplayName("승인자 빈 리스트는 거부")
        void emptyApproversIsRejected() {
            String fileId = "err_empty";
            documentService.createInitialVersion(ALICE, fileId, 1024, "text/plain");

            assertThatThrownBy(() -> documentService.requestApproval(
                    ALICE, fileId, "no approvers", List.of()))
                    .hasMessageContaining("Approvers list cannot be empty");
        }

        @Test
        @DisplayName("이미 진행 중인 승인 요청이 있으면 중복 요청 거부")
        void duplicateApprovalRequestIsRejected() {
            String fileId = "err_dup_req";
            documentService.createInitialVersion(ALICE, fileId, 1024, "text/plain");
            documentService.requestApproval(ALICE, fileId, "1st", List.of(BOB));

            // 같은 파일에 또 요청 — 거부
            assertThatThrownBy(() -> documentService.requestApproval(
                    ALICE, fileId, "2nd", List.of(BOB)))
                    .hasMessageContaining("already pending");
        }
    }

    // ============================================================
    // 알림 읽음 처리 (의사코드 ⑥ 단계)
    // ============================================================

    @Nested
    @DisplayName("알림 읽음 처리")
    class NotificationReadTests {

        @Test
        @DisplayName("단건 읽음 처리 후 unreadCount 감소")
        void markRead_decreasesUnreadCount() {
            String fileId = "notif_read_1";
            documentService.createInitialVersion(ALICE, fileId, 1024, "text/plain");

            assertThat(notificationService.getUnreadCount(ALICE)).isEqualTo(1);

            List<NotificationInfo> notifs = notificationService.getUserNotifications(ALICE, true, 20, 0);
            String notifId = notifs.get(0).getNotificationId();

            boolean marked = notificationService.markNotificationRead(ALICE, notifId);
            assertThat(marked).isTrue();
            assertThat(notificationService.getUnreadCount(ALICE)).isEqualTo(0);
        }

        @Test
        @DisplayName("이미 읽은 알림 재처리는 false 반환 (최초 읽은 시각 보존)")
        void markRead_twiceReturnsFalse() {
            String fileId = "notif_read_2";
            documentService.createInitialVersion(ALICE, fileId, 1024, "text/plain");
            String notifId = notificationService.getUserNotifications(ALICE, true, 20, 0)
                    .get(0).getNotificationId();

            notificationService.markNotificationRead(ALICE, notifId);
            // 두 번째 호출
            boolean secondMark = notificationService.markNotificationRead(ALICE, notifId);
            assertThat(secondMark).isFalse();
        }

        @Test
        @DisplayName("다른 사용자의 알림은 읽음 처리 불가 (보안)")
        void markRead_otherUsersNotificationFails() {
            String fileId = "notif_read_3";
            documentService.createInitialVersion(ALICE, fileId, 1024, "text/plain");
            String aliceNotifId = notificationService.getUserNotifications(ALICE, true, 20, 0)
                    .get(0).getNotificationId();

            // bob이 alice 알림 읽음 처리 시도 — affected 0 → false
            boolean result = notificationService.markNotificationRead(BOB, aliceNotifId);
            assertThat(result).isFalse();

            // alice의 unread count는 그대로
            assertThat(notificationService.getUnreadCount(ALICE)).isEqualTo(1);
        }
    }
}
