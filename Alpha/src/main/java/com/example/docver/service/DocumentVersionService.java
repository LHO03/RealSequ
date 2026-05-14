package com.example.docver.service;

import com.example.docver.exception.WorkflowException;
import com.example.docver.model.ApprovalAction;
import com.example.docver.model.DocumentStatus;
import com.example.docver.model.VersionInfo;
import com.example.docver.repository.ActivityRepository;
import com.example.docver.repository.ApprovalRepository;
import com.example.docver.repository.FileVersionRepository;
import com.example.docver.repository.SystemTagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 문서 버전 + 워크플로우 핵심 서비스.
 *
 * 의사코드의 5개 메서드 통합 이식:
 *   - createInitialVersion       → createInitialVersion
 *   - onDocumentModified         → updateDocument
 *   - setDocumentStatus          → (내부 헬퍼) updateStatus
 *   - processApprovalWorkflow    → requestApproval / decideApproval / 분기
 *   - processApprovalDecision    → (내부 헬퍼) processDecision
 *
 * 시연 단순화 (의도적 제외):
 *   - 합의 모델 다중 지원: THRESHOLD + required=1 고정
 *   - 위임/대리 승인 (의사코드 ① 단계)
 *   - CANCEL 액션
 *   - SEQUENTIAL 차례 검증
 *   - 보존 정책 자동 적용 (시연에서 onDocumentModified가 정책 호출 안 함)
 *   - DiffService 실제 계산 ("Modified by {user}" 단순 메시지)
 *
 * 이식 시 의사코드와의 차이:
 *   - C++ versionCounter (멤버 변수) → AtomicLong 사용 (동시성 안전)
 *   - 트랜잭션 명시: @Transactional (의사코드 line 323의 "트랜잭션 처리 문제" 해소)
 *   - 예외 처리: WorkflowException으로 명시
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentVersionService {

    private final FileVersionRepository fileVersionRepo;
    private final SystemTagRepository systemTagRepo;
    private final ApprovalRepository approvalRepo;
    private final ActivityRepository activityRepo;
    private final NotificationService notificationService;

    /**
     * 의사코드 versionCounter 멤버 변수 → Java에서는 AtomicLong.
     * 1초 내 다중 버전 생성 시 ID 충돌 방지 (의사코드 03/18 수정사항).
     */
    private final AtomicLong versionCounter = new AtomicLong(0);

    // ============================================================
    // RD-SRS-9.1, 9.2: 버전 생성/업데이트
    // ============================================================

    /**
     * 의사코드 createInitialVersion (line 268) 이식.
     *
     * 동작:
     *   1. 버전 ID 생성 ({fileId}.v{timestamp}_{counter})
     *   2. files_versions INSERT
     *   3. 활동 로그
     *   4. 자동 트리거: version_created 알림
     *
     * 시연 단순화:
     *   - fileStorage 호출 제외 (파일 시스템 쓰기는 데모에서 무의미)
     *   - 호출자가 fileId를 명시 (의사코드의 generateFileId 단순화)
     *
     * @return 생성된 VersionInfo
     */
    @Transactional
    public VersionInfo createInitialVersion(String userId, String fileId,
                                             long size, String mimeType) {
        long timestamp = System.currentTimeMillis() / 1000;
        String versionId = fileId + ".v" + timestamp + "_" + versionCounter.getAndIncrement();

        VersionInfo version = VersionInfo.builder()
                .versionId(versionId)
                .fileId(fileId)
                .userId(userId)
                .timestamp(timestamp)
                .size(size)
                .mimeType(mimeType)
                .metadata(buildMetadata(userId))
                .build();

        fileVersionRepo.insert(version);
        activityRepo.log(userId, fileId, "version_created", "Initial version");

        // 자동 트리거 (의사코드 line 318)
        // [Java 전환 차후] @EventPublisher로 VersionCreatedEvent 발행 → EventListener에서 알림
        notificationService.notifyStakeholders(fileId, "version_created",
                "Initial version created by " + userId, List.of());

        log.info("Initial version created: {}", versionId);
        return version;
    }

    /**
     * 의사코드 onDocumentModified (line 330) 이식.
     *
     * 동작:
     *   1. 새 버전 ID 생성
     *   2. files_versions INSERT (수정 전 콘텐츠 백업)
     *   3. 활동 로그
     *   4. 자동 트리거: version_updated 알림
     *
     * 시연 단순화:
     *   - DiffService 호출 제외 ("Modified by {user}" 단순 메시지)
     *   - 보존 정책 cascade 제외
     *   - fileStorage 호출 제외
     *
     * 의사코드와의 의미 일치:
     *   - 수정 직전 콘텐츠의 백업이라는 점 (수정 후가 아님)
     *   - 자동 트리거 이벤트는 "version_updated" (수정본은 #1에서 fix됨)
     */
    @Transactional
    public VersionInfo updateDocument(String userId, String fileId,
                                       long newSize, String mimeType) {
        long timestamp = System.currentTimeMillis() / 1000;
        String versionId = fileId + ".v" + timestamp + "_" + versionCounter.getAndIncrement();

        VersionInfo version = VersionInfo.builder()
                .versionId(versionId)
                .fileId(fileId)
                .userId(userId)
                .timestamp(timestamp)
                .size(newSize)
                .mimeType(mimeType)
                .metadata(buildMetadata(userId))
                .build();

        fileVersionRepo.insert(version);
        activityRepo.log(userId, fileId, "file_modified",
                "Modified by " + userId + " (size: " + newSize + " bytes)");

        // 자동 트리거 (의사코드 line 422의 수정사항 #1 반영)
        notificationService.notifyStakeholders(fileId, "version_updated",
                "New version " + versionId + " by " + userId, List.of());

        log.info("Document updated: file={}, newVersion={}", fileId, versionId);
        return version;
    }

    // ============================================================
    // RD-SRS-9.6: 문서 상태 관리 (내부 헬퍼)
    // ============================================================

    /**
     * 의사코드 setDocumentStatus (line 766) 이식 (private 헬퍼).
     *
     * 동작:
     *   1. 상태 전이 유효성 검사
     *   2. 태그 ID 조회 (없으면 생성)
     *   3. systemtag_object_mapping 갱신 (REPLACE INTO)
     *   4. 활동 로그
     *
     * 시연 단순화:
     *   - 사용자 알림 발송은 호출자가 별도 처리 (notifyStakeholders)
     *   - 매트릭스 커스터마이징 인터페이스 제외 (기본 매트릭스만)
     *
     * @return 전이 성공 여부 (실패 시 false, 호출자가 후속 처리 결정)
     */
    private boolean updateStatus(String userId, String fileId, DocumentStatus status, String comment) {
        String tagName = status.toTagName();

        // 상태 전이 유효성 검사
        String currentTag = systemTagRepo.findCurrentStatusTag(fileId);
        if (!isValidTransition(currentTag, tagName)) {
            String display = currentTag.isEmpty() ? "(none)" : currentTag;
            activityRepo.log(userId, fileId, "status_change_denied",
                    "Invalid transition: " + display + " -> " + tagName);
            log.warn("Invalid transition denied: {} -> {} for file {}", display, tagName, fileId);
            return false;
        }

        // 태그 ID 조회 (시드 데이터에 미리 등록되어 있음)
        String tagId = systemTagRepo.findTagIdByName(tagName);
        if (tagId.isEmpty()) {
            throw new WorkflowException("Status tag not found: " + tagName +
                    " (seed data missing?)");
        }

        // 태그 할당 (REPLACE INTO로 기존 status 태그 자동 교체)
        systemTagRepo.assignTag(fileId, tagId);

        activityRepo.log(userId, fileId, "status_changed",
                "Changed to " + tagName + ": " + (comment == null ? "" : comment));
        log.info("Status changed: file={}, {} -> {}", fileId, currentTag, tagName);
        return true;
    }

    /**
     * 의사코드 isValidTransition (line 3310) 이식.
     *
     * 시연용 기본 매트릭스 (보고서 표):
     *   (없음)       → DRAFT, UNDER_REVIEW
     *   DRAFT        → UNDER_REVIEW, DEPRECATED
     *   UNDER_REVIEW → APPROVED, REJECTED, DRAFT
     *   APPROVED     → DEPRECATED
     *   REJECTED     → DRAFT, DEPRECATED
     *   DEPRECATED   → (전이 불가)
     */
    private boolean isValidTransition(String currentTag, String newTag) {
        Map<String, Set<String>> matrix = Map.of(
                "",             Set.of("draft", "under_review"),
                "draft",        Set.of("under_review", "deprecated"),
                "under_review", Set.of("approved", "rejected", "draft"),
                "approved",     Set.of("deprecated"),
                "rejected",     Set.of("draft", "deprecated"),
                "deprecated",   Set.of()
        );
        Set<String> allowed = matrix.get(currentTag);
        return allowed != null && allowed.contains(newTag);
    }

    // ============================================================
    // RD-SRS-9.7: 승인 워크플로우
    // ============================================================

    /**
     * 의사코드 processApprovalWorkflow의 REQUEST 분기 이식.
     *
     * 동작:
     *   1. 빈 승인자 방어
     *   2. 중복 요청 방어 (under_review 태그 이미 할당 시)
     *   3. 상태 전이: → UNDER_REVIEW
     *   4. approval_rules + requesters + approvers INSERT
     *   5. 자동 트리거: approval_requested 알림 (승인자 명시 targets)
     *
     * 시연 단순화:
     *   - 합의 모드 THRESHOLD + required=1 고정 (insertRule에 하드코딩)
     *   - 중복 승인자 dedup은 INSERT IGNORE로 처리 (의사코드 수정사항 #3 반영)
     */
    @Transactional
    public String requestApproval(String userId, String fileId, String comment,
                                   List<String> approvers) {
        if (approvers == null || approvers.isEmpty()) {
            activityRepo.log(userId, fileId, "approval_failed",
                    "No approvers specified");
            throw new WorkflowException("Approvers list cannot be empty");
        }

        // 중복 요청 방어
        if (approvalRepo.hasOpenPendingForFile(fileId, "under_review")) {
            activityRepo.log(userId, fileId, "approval_failed",
                    "Approval already pending");
            throw new WorkflowException("Approval already pending for this file");
        }

        // 상태 전이
        if (!updateStatus(userId, fileId, DocumentStatus.UNDER_REVIEW,
                "Approval requested: " + comment)) {
            activityRepo.log(userId, fileId, "approval_failed",
                    "Failed to transition to UNDER_REVIEW");
            throw new WorkflowException("Status transition to UNDER_REVIEW failed");
        }

        // 규칙 생성
        String ruleId = "rule_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        approvalRepo.insertRule(ruleId, fileId, "under_review", "approved", "rejected");
        approvalRepo.insertRequester(ruleId, userId);

        for (String approver : approvers) {
            approvalRepo.insertApprover(ruleId, approver);  // INSERT IGNORE로 중복 방어
        }

        // 자동 트리거: 승인자 명시 targets
        notificationService.notifyStakeholders(fileId, "approval_requested",
                "User " + userId + " requested your approval. Comment: " + comment,
                approvers);

        log.info("Approval requested: rule={}, requester={}, approvers={}",
                ruleId, userId, approvers);
        return ruleId;
    }

    /**
     * 의사코드 processApprovalWorkflow + processApprovalDecision의 APPROVE/REJECT 통합.
     *
     * 시연 단순화 (의사코드 대비):
     *   - 위임 평가 제외
     *   - SEQUENTIAL 차례 검증 제외
     *   - 합의 평가는 THRESHOLD + required=1이므로 즉시 확정
     *
     * @param action APPROVE 또는 REJECT
     */
    @Transactional
    public boolean decideApproval(String userId, String fileId, ApprovalAction action, String comment) {
        if (action != ApprovalAction.APPROVE && action != ApprovalAction.REJECT) {
            throw new WorkflowException("Only APPROVE/REJECT supported in demo: " + action);
        }

        // 1. 승인 권한 확인 (OPEN 규칙 중 본인이 승인자인지)
        String ruleId = approvalRepo.findOpenRuleIdForApprover(fileId, userId, "under_review")
                .orElseThrow(() -> {
                    activityRepo.log(userId, fileId, "approval_denied", "Not an approver");
                    return new WorkflowException("User is not an approver for this file");
                });

        // 2. 재결정 차단
        if (approvalRepo.hasPriorDecision(ruleId, userId)) {
            activityRepo.log(userId, fileId, "approval_duplicate_denied",
                    "User already decided on rule " + ruleId);
            throw new WorkflowException("User already made a decision on this rule");
        }

        // 3. activity 기록
        boolean isApprove = (action == ApprovalAction.APPROVE);
        String actionTag = isApprove ? "approved" : "rejected";
        long timestamp = System.currentTimeMillis() / 1000;

        approvalRepo.insertActivity(ruleId, userId, actionTag, timestamp, comment);
        approvalRepo.incrementCounter(ruleId, isApprove);

        // 4. 합의 평가 (THRESHOLD + required=1: 첫 결정으로 즉시 확정)
        //    의사코드 evaluateConsensus 단순화: 본 시연은 단일 모드라 즉시 finalStatus 결정 가능
        DocumentStatus finalStatus = isApprove ? DocumentStatus.APPROVED : DocumentStatus.REJECTED;
        String finalActionVerb = isApprove ? "approved" : "rejected";

        // 5. 상태 전이
        String statusComment = capitalize(finalActionVerb) + " (consensus reached): " + comment;
        if (!updateStatus(userId, fileId, finalStatus, statusComment)) {
            activityRepo.log(userId, fileId, "approval_" + finalActionVerb + "_failed",
                    "Status transition to " + actionTag + " failed");
            throw new WorkflowException("Status transition failed");
        }

        // 6. 요청자에게 personalized 알림 (수정사항 #4 반영: notifyStakeholders로 통합)
        approvalRepo.findRequesterByRuleId(ruleId).ifPresent(requesterId -> {
            String body = "File " + fileId + " has been " + finalActionVerb +
                    " (decided by " + userId + ")";
            if (comment != null && !comment.isEmpty()) {
                body += ". Comment: " + comment;
            }
            notificationService.notifyStakeholders(fileId, "approval_completed",
                    "Your document was " + finalActionVerb + ": " + body,
                    List.of(requesterId));
        });

        // 7. 규칙 종료
        approvalRepo.closeRule(ruleId);

        log.info("Approval decided: rule={}, decider={}, action={}", ruleId, userId, actionTag);
        return true;
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private String buildMetadata(String userId) {
        // 의사코드 buildVersionMetadataJson 단순화 (Java 전환 차후 Jackson 사용)
        Map<String, String> meta = new HashMap<>();
        meta.put("author", userId);
        return "{\"author\":\"" + userId.replace("\"", "\\\"") + "\"}";
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}