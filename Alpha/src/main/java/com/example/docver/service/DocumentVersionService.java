package com.example.docver.service;

import com.example.docver.exception.WorkflowException;
import com.example.docver.model.ApprovalAction;
import com.example.docver.model.ApprovalProgress;
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
     * 05/15 확장:
     *   - 합의 모드 매개변수 추가 (THRESHOLD, UNANIMOUS, SEQUENTIAL)
     *   - required_approvals 매개변수 추가 (THRESHOLD 모드용)
     *   - SEQUENTIAL 모드 시 approvers 순서대로 sequence_order 1, 2, 3... 부여
     */
    @Transactional
    public String requestApproval(String userId, String fileId, String comment,
                                   List<String> approvers, String consensusMode, int requiredApprovals) {
        if (approvers == null || approvers.isEmpty()) {
            activityRepo.log(userId, fileId, "approval_failed",
                    "No approvers specified");
            throw new WorkflowException("Approvers list cannot be empty");
        }

        // 합의 모드 검증
        if (consensusMode == null) consensusMode = "THRESHOLD";
        if (!consensusMode.equals("THRESHOLD")
                && !consensusMode.equals("UNANIMOUS")
                && !consensusMode.equals("SEQUENTIAL")) {
            throw new WorkflowException("Unknown consensus mode: " + consensusMode);
        }

        // requiredApprovals 검증 (THRESHOLD 모드에서만 의미 있음)
        if (requiredApprovals < 1) requiredApprovals = 1;
        if (requiredApprovals > approvers.size()) {
            throw new WorkflowException("requiredApprovals exceeds approvers count");
        }

        // UNANIMOUS와 SEQUENTIAL은 required를 사용하지 않지만 일관성 위해 totalApprovers로 정규화
        if (consensusMode.equals("UNANIMOUS") || consensusMode.equals("SEQUENTIAL")) {
            requiredApprovals = approvers.size();
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
        approvalRepo.insertRule(ruleId, fileId, "under_review", "approved", "rejected",
                consensusMode, requiredApprovals);
        approvalRepo.insertRequester(ruleId, userId);

        // 승인자 등록 (SEQUENTIAL 모드면 순서 부여, 그 외는 0)
        int seqOrder = 1;
        for (String approver : approvers) {
            if (consensusMode.equals("SEQUENTIAL")) {
                approvalRepo.insertApprover(ruleId, approver, seqOrder++);
            } else {
                approvalRepo.insertApprover(ruleId, approver, 0);  // INSERT IGNORE로 중복 방어
            }
        }

        // 자동 트리거: 승인자 명시 targets
        String modeDesc = consensusMode.equals("THRESHOLD")
                ? "THRESHOLD (" + requiredApprovals + "/" + approvers.size() + ")"
                : consensusMode;
        notificationService.notifyStakeholders(fileId, "approval_requested",
                "User " + userId + " requested your approval [" + modeDesc + "]. Comment: " + comment,
                approvers);

        log.info("Approval requested: rule={}, requester={}, approvers={}, mode={}",
                ruleId, userId, approvers, consensusMode);
        return ruleId;
    }

    /**
     * Backward compat: 기존 호출 (THRESHOLD + required=1).
     */
    @Transactional
    public String requestApproval(String userId, String fileId, String comment,
                                   List<String> approvers) {
        return requestApproval(userId, fileId, comment, approvers, "THRESHOLD", 1);
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
            throw new WorkflowException("Only APPROVE/REJECT supported in decideApproval: " + action);
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

        // 2-1. SEQUENTIAL 모드 차례 검증 (05/15 추가)
        //      의사코드 isUserTurnInSequence (line 3243) 이식.
        if (!isUserTurnInSequence(ruleId, userId)) {
            activityRepo.log(userId, fileId, "approval_out_of_turn",
                    "User attempted to decide before their turn in SEQUENTIAL mode");
            throw new WorkflowException("Not your turn in SEQUENTIAL mode");
        }

        // 3. activity 기록 (합의 평가 입력 보장)
        boolean isApprove = (action == ApprovalAction.APPROVE);
        String actionTag = isApprove ? "approved" : "rejected";
        long timestamp = System.currentTimeMillis() / 1000;

        approvalRepo.insertActivity(ruleId, userId, actionTag, timestamp, comment);
        approvalRepo.incrementCounter(ruleId, isApprove);

        // 4. 합의 평가 (05/15 확장 — 다중 모드 지원)
        //    의사코드 evaluateConsensus (line 3287) 이식.
        //    반환값: "PENDING" / "APPROVED" / "REJECTED"
        String consensus = evaluateConsensus(ruleId);

        if ("PENDING".equals(consensus)) {
            // 아직 합의 미도달 — 상태 전이 없음, 규칙 OPEN 유지
            log.info("Approval decision recorded but consensus pending: rule={}, decider={}, action={}",
                    ruleId, userId, actionTag);
            // 부분 진행 알림 (요청자에게 진행 상황 알림)
            approvalRepo.findRequesterByRuleId(ruleId).ifPresent(requesterId -> {
                notificationService.notifyStakeholders(fileId, "approval_progress",
                        "User " + userId + " has " + actionTag + ". Awaiting more decisions.",
                        List.of(requesterId));
            });
            return true;
        }

        // 합의 도달 (APPROVED 또는 REJECTED) — 최종 처리
        DocumentStatus finalStatus = "APPROVED".equals(consensus)
                ? DocumentStatus.APPROVED : DocumentStatus.REJECTED;
        String finalActionVerb = "APPROVED".equals(consensus) ? "approved" : "rejected";

        // 5. 상태 전이
        String statusComment = capitalize(finalActionVerb) + " (consensus reached): " + comment;
        if (!updateStatus(userId, fileId, finalStatus, statusComment)) {
            activityRepo.log(userId, fileId, "approval_" + finalActionVerb + "_failed",
                    "Status transition to " + actionTag + " failed");
            throw new WorkflowException("Status transition failed");
        }

        // 6. 요청자에게 personalized 알림 (검토 #4 반영: notifyStakeholders로 통합)
        approvalRepo.findRequesterByRuleId(ruleId).ifPresent(requesterId -> {
            String body = "File " + fileId + " has been " + finalActionVerb +
                    " (final decision by " + userId + ")";
            if (comment != null && !comment.isEmpty()) {
                body += ". Comment: " + comment;
            }
            notificationService.notifyStakeholders(fileId, "approval_completed",
                    "Your document was " + finalActionVerb + ": " + body,
                    List.of(requesterId));
        });

        // 7. 규칙 종료
        approvalRepo.closeRule(ruleId);

        log.info("Approval decided: rule={}, decider={}, action={}, consensus={}",
                ruleId, userId, actionTag, consensus);
        return true;
    }

    /**
     * 의사코드 evaluateConsensus (line 3287) 이식.
     *
     * THRESHOLD:
     *   - received_approvals >= required → APPROVED
     *   - received_approvals + 남은승인자수 < required → REJECTED (도달 불가)
     *   - 그 외 → PENDING
     *
     * UNANIMOUS / SEQUENTIAL (둘 다 동일 로직):
     *   - received_rejections > 0 → REJECTED (한 명이라도 거절 시 즉시 종료)
     *   - received_approvals == 총 승인자 수 → APPROVED
     *   - 그 외 → PENDING
     *
     * @return "PENDING", "APPROVED", "REJECTED"
     */
    private String evaluateConsensus(String ruleId) {
        Map<String, Object> ruleOpt = approvalRepo.findCountersByRuleId(ruleId);
        if (ruleOpt == null || ruleOpt.isEmpty()) {
            return "PENDING";  // 안전 기본값
        }

        String mode = String.valueOf(ruleOpt.get("consensus_mode"));
        int required = toInt(ruleOpt.get("required_approvals"));
        int receivedApprovals = toInt(ruleOpt.get("received_approvals"));
        int receivedRejections = toInt(ruleOpt.get("received_rejections"));

        int totalApprovers = (int) approvalRepo.countApproversByRuleId(ruleId);

        if ("THRESHOLD".equals(mode)) {
            if (receivedApprovals >= required) return "APPROVED";
            int remaining = totalApprovers - receivedApprovals - receivedRejections;
            if (receivedApprovals + remaining < required) return "REJECTED";
            return "PENDING";
        } else {
            // UNANIMOUS / SEQUENTIAL (동일 로직)
            if (receivedRejections > 0) return "REJECTED";
            if (receivedApprovals == totalApprovers) return "APPROVED";
            return "PENDING";
        }
    }

    /**
     * 의사코드 isUserTurnInSequence (line 3243) 이식.
     * SEQUENTIAL 모드에서 본인 앞 순서의 승인자가 모두 결정 완료했는지 확인.
     *
     * THRESHOLD, UNANIMOUS는 순서 무관 → 항상 true.
     */
    private boolean isUserTurnInSequence(String ruleId, String userId) {
        Map<String, Object> ruleOpt = approvalRepo.findCountersByRuleId(ruleId);
        if (ruleOpt == null || ruleOpt.isEmpty()) return false;

        String mode = String.valueOf(ruleOpt.get("consensus_mode"));
        if (!"SEQUENTIAL".equals(mode)) {
            return true;  // 순서 무관 모드
        }

        Integer mySeq = approvalRepo.findSequenceOrderForUser(ruleId, userId).orElse(null);
        if (mySeq == null) return false;

        int pendingBefore = approvalRepo.countPendingBefore(ruleId, mySeq);
        return pendingBefore == 0;
    }

    /**
     * 의사코드 cancelApprovalRequest (line 1057) 이식.
     *
     * 요청자 / 승인자가 진행 중인 승인 요청을 취소.
     * ADMIN 권한은 본 이식에서 미구현 (시연 외).
     *
     * 동작:
     *   1. OPEN 상태 규칙 조회 (없으면 false)
     *   2. 권한 체크 (요청자 또는 승인자만)
     *   3. 규칙 상태 OPEN → CANCELLED
     *   4. 활동 이력 기록 (cancelled action)
     *   5. 상태 전이: UNDER_REVIEW → DRAFT
     *   6. 이해관계자 broadcast 알림
     */
    @Transactional
    public boolean cancelApprovalRequest(String userId, String fileId, String comment) {
        // 1. OPEN 규칙 조회
        String ruleId = approvalRepo.findOpenRuleIdByFileId(fileId).orElse(null);
        if (ruleId == null) {
            activityRepo.log(userId, fileId, "approval_cancel_failed",
                    "No open approval request found");
            throw new WorkflowException("No open approval request to cancel");
        }

        // 2. 권한 체크 (의사코드 line 1073~1096)
        boolean isRequester = approvalRepo.isRequester(ruleId, userId);
        boolean isApprover = approvalRepo.isApprover(ruleId, userId);
        // ADMIN 체크는 본 이식에서 미구현 (의사코드 isAdmin 이식 별도 작업)

        if (!isRequester && !isApprover) {
            activityRepo.log(userId, fileId, "approval_cancel_denied",
                    "User has no permission to cancel rule " + ruleId);
            throw new WorkflowException("User cannot cancel this approval");
        }

        // 3. 규칙 상태 변경 (race condition 방어)
        int affected = approvalRepo.cancelRule(ruleId);
        if (affected == 0) {
            // 다른 호출이 먼저 닫음
            throw new WorkflowException("Rule already closed (race condition)");
        }

        // 4. 활동 이력 기록
        long timestamp = System.currentTimeMillis() / 1000;
        String actorRole = isRequester ? "requester" : "approver";
        String activityComment = "Cancelled by " + actorRole +
                (comment == null || comment.isEmpty() ? "" : ": " + comment);
        approvalRepo.insertActivity(ruleId, userId, "cancelled", timestamp, activityComment);

        // 5. 상태 전이: UNDER_REVIEW → DRAFT
        if (!updateStatus(userId, fileId, DocumentStatus.DRAFT, "Approval request cancelled")) {
            // 의사코드 line 1129~1133: 부분 실패 — 로그만 남기고 true 반환
            // rule은 이미 CANCELLED 상태
            activityRepo.log(userId, fileId, "approval_cancel_partial",
                    "Rule cancelled but status revert failed for rule " + ruleId);
        }

        // 6. 이해관계자 broadcast 알림 (자동 트리거)
        String notifyMsg = "Approval request was cancelled by " + actorRole +
                " (" + userId + ")" +
                (comment == null || comment.isEmpty() ? "" : ". Reason: " + comment);
        notificationService.notifyStakeholders(fileId, "approval_cancelled",
                notifyMsg, List.of());

        log.info("Approval cancelled: rule={}, actor={}, role={}", ruleId, userId, actorRole);
        return true;
    }

    /**
     * 정수 변환 헬퍼 (Object → int).
     * MariaDB JDBC가 BIGINT, INT 등을 다양한 타입으로 반환할 수 있어 통일.
     */
    private int toInt(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number) return ((Number) obj).intValue();
        try {
            return Integer.parseInt(String.valueOf(obj));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ============================================================
    // Group A: 조회 API (05/15 추가)
    // ============================================================

    /**
     * 의사코드 getCurrentStatusTag (line 3336) 이식.
     * 파일의 현재 상태 태그명을 반환. 상태 미지정이면 빈 문자열.
     *
     * 예: "draft", "under_review", "approved", "rejected", "deprecated", ""
     */
    public String getFileStatus(String fileId) {
        return systemTagRepo.findCurrentStatusTag(fileId);
    }

    /**
     * 의사코드 getVersionsAtTime + countVersions (line 666~750) 이식 (단순화).
     *
     * 시연 단순화 사항:
     *   - targetTimestamp 매개변수 생략 (전체 버전 반환)
     *   - 모든 버전 정보 한 번에 반환 (페이지네이션 limit 100 고정)
     *
     * 의사코드와 동일한 부분:
     *   - 시간 역순 (최신 먼저)
     *   - limit/offset clamp (1~100, 음수 방어)
     */
    public Map<String, Object> getVersions(String fileId, int limit, int offset) {
        // 의사코드 line 688~693의 clamp 로직과 동일
        if (limit < 1) limit = 1;
        if (limit > 100) limit = 100;
        if (offset < 0) offset = 0;

        List<VersionInfo> versions = fileVersionRepo.findByFileIdDesc(fileId, limit, offset);
        long total = fileVersionRepo.countByFileId(fileId);

        Map<String, Object> result = new HashMap<>();
        result.put("versions", versions);
        result.put("total", total);
        result.put("limit", limit);
        result.put("offset", offset);
        return result;
    }

    /**
     * 의사코드 getApprovalProgress (line 2912) 이식.
     *
     * 파일의 현재(또는 가장 최근) 승인 규칙에 대한 진행 상황을 반환.
     * OPEN 규칙 우선, 없으면 가장 최근 CLOSED/CANCELLED.
     *
     * 반환 구조:
     *   - rule: 규칙 메타 (id, file_id, status, mode 등)
     *   - counters: 카운터 (required, received_approvals, received_rejections, total_approvers, mode)
     *   - decisions: 결정 이력 (시간순)
     *   - pending: 미결 승인자 (sequence_order 순)
     *
     * 승인 이력 자체가 없으면 빈 ApprovalProgress 반환.
     */
    public ApprovalProgress getApprovalProgress(String fileId) {
        // 1. 가장 관련성 높은 규칙 조회
        Map<String, Object> ruleOpt = approvalRepo.findLatestRuleByFileId(fileId).orElse(null);
        if (ruleOpt == null) {
            return ApprovalProgress.empty();
        }

        String ruleId = String.valueOf(ruleOpt.get("id"));

        // 2. 카운터 구성
        long totalApprovers = approvalRepo.countApproversByRuleId(ruleId);
        Map<String, Object> counters = new HashMap<>();
        counters.put("required", ruleOpt.get("required_approvals"));
        counters.put("received_approvals", ruleOpt.get("received_approvals"));
        counters.put("received_rejections", ruleOpt.get("received_rejections"));
        counters.put("total_approvers", totalApprovers);
        counters.put("mode", ruleOpt.get("consensus_mode"));

        // 3. 결정 이력
        List<Map<String, Object>> decisions = approvalRepo.findDecisionHistoryByRuleId(ruleId);

        // 4. 미결 승인자
        List<Map<String, Object>> pending = approvalRepo.findPendingApproversByRuleId(ruleId);

        return ApprovalProgress.builder()
                .rule(ruleOpt)
                .counters(counters)
                .decisions(decisions)
                .pending(pending)
                .build();
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
