package com.docversion.service;

import com.docversion.mapper.ApprovalMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 승인 워크플로 (RD-SRS-9.7) — 단일 승인자.
 *
 * 규칙:
 *  - 문서가 "검토중(UNDER_REVIEW)"일 때만 승인 요청을 생성할 수 있다.
 *  - 자기 승인 금지: 요청자 ≠ 승인자 (문자열 비교; 계정/인증은 후속 과제).
 *  - 문서당 열린(OPEN) 요청은 하나만 (DB의 uq_open_per_file로도 보장).
 *  - 승인 → 문서 상태 APPROVED, 반려/취소 → DRAFT. 상태 변경은 9.6의
 *    DocumentLifecycleService.changeStatus를 호출하여 전이 규칙·이력을 그대로 적용.
 *  - 모든 판정은 (요청 닫기 + 이력 + 문서 상태 변경)을 한 트랜잭션으로 묶는다.
 */
@Service
public class ApprovalService {

    private final ApprovalMapper mapper;
    private final DocumentLifecycleService lifecycle;
    private final NotificationService notifications;
    private final UuidGenerator uuid;

    public ApprovalService(ApprovalMapper mapper, DocumentLifecycleService lifecycle,
                           NotificationService notifications, UuidGenerator uuid) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.notifications = notifications;
        this.uuid = uuid;
    }

    /** 현재 열린 요청(없으면 null) + 요청 이력. */
    public record ApprovalState(Map<String, Object> open, List<Map<String, Object>> history) {
    }

    public ApprovalState getState(String fileId) {
        return new ApprovalState(mapper.findOpenByFile(fileId), mapper.listByFile(fileId));
    }

    /** 승인 요청 생성. */
    @Transactional
    public ApprovalState request(String fileId, String requesterId, String approverId, String comment) {
        if (approverId == null || approverId.isBlank()) {
            throw new IllegalArgumentException("승인자를 지정해야 합니다.");
        }
        approverId = approverId.trim();
        if (requesterId.equals(approverId)) {
            throw new IllegalStateException("자기 자신을 승인자로 지정할 수 없습니다. 다른 승인자를 지정하십시오.");
        }
        // 검토중 상태에서만 요청 가능
        String status = lifecycle.getStatus(fileId).status();
        if (!"UNDER_REVIEW".equals(status)) {
            throw new IllegalStateException("검토중 상태에서만 승인 요청을 생성할 수 있습니다. (먼저 문서를 검토 제출하십시오.)");
        }
        if (mapper.findOpenByFile(fileId) != null) {
            throw new IllegalStateException("이미 처리 대기 중인 승인 요청이 있습니다.");
        }
        long now = Instant.now().getEpochSecond();
        String id = uuid.newId();
        try {
            mapper.insertRequest(id, fileId, requesterId, approverId, now);
        } catch (DuplicateKeyException e) {
            // 동시 요청 경합 시 DB의 uq_open_per_file이 두 번째를 차단
            throw new IllegalStateException("이미 처리 대기 중인 승인 요청이 있습니다.");
        }
        mapper.insertActivity(id, requesterId, "REQUESTED",
                (comment == null || comment.isBlank()) ? null : comment.trim(), now);
        // 이해관계자 등록 + 승인자에게 알림 (같은 트랜잭션)
        notifications.subscribe(fileId, requesterId);
        notifications.subscribe(fileId, approverId);
        notifications.notifyStakeholders(fileId, "승인 요청",
                requesterId + "님이 승인을 요청했습니다. (승인자: " + approverId + ")", requesterId);
        return getState(fileId);
    }

    /** 승인 처리: 지정된 승인자만. 문서 → APPROVED. */
    @Transactional
    public ApprovalState approve(String fileId, String actorId, String comment) {
        return decide(fileId, actorId, comment, true);
    }

    /** 반려 처리: 지정된 승인자만. 문서 → DRAFT. */
    @Transactional
    public ApprovalState reject(String fileId, String actorId, String comment) {
        return decide(fileId, actorId, comment, false);
    }

    private ApprovalState decide(String fileId, String actorId, String comment, boolean approved) {
        Map<String, Object> open = mapper.findOpenByFile(fileId);
        if (open == null) {
            throw new IllegalStateException("처리할 승인 요청이 없습니다.");
        }
        String approver = String.valueOf(open.get("approverId"));
        if (!approver.equals(actorId)) {
            throw new IllegalStateException("지정된 승인자(" + approver + ")만 처리할 수 있습니다.");
        }
        String id = String.valueOf(open.get("id"));
        long now = Instant.now().getEpochSecond();
        String c = (comment == null || comment.isBlank()) ? null : comment.trim();

        int closed = mapper.closeRequest(id, approved ? "APPROVED" : "REJECTED", now);
        if (closed == 0) {
            throw new IllegalStateException("이미 처리된 요청입니다.");
        }
        mapper.insertActivity(id, actorId, approved ? "APPROVED" : "REJECTED", c, now);
        // 9.6 상태 전이: 승인→APPROVED, 반려→DRAFT (전이 규칙·이력 그대로 적용)
        lifecycle.changeStatus(fileId, actorId, approved ? "APPROVED" : "DRAFT", c);
        // 요청자 등 이해관계자에게 결과 알림
        notifications.notifyStakeholders(fileId, approved ? "승인됨" : "반려됨",
                approved ? (actorId + "님이 문서를 승인했습니다.") : (actorId + "님이 문서를 반려했습니다."), actorId);
        return getState(fileId);
    }

    /** 요청 취소: 요청자만. 문서 → DRAFT (검토를 접고 초안으로 되돌림). */
    @Transactional
    public ApprovalState cancel(String fileId, String actorId, String comment) {
        Map<String, Object> open = mapper.findOpenByFile(fileId);
        if (open == null) {
            throw new IllegalStateException("취소할 승인 요청이 없습니다.");
        }
        String requester = String.valueOf(open.get("requesterId"));
        if (!requester.equals(actorId)) {
            throw new IllegalStateException("요청자(" + requester + ")만 취소할 수 있습니다.");
        }
        String id = String.valueOf(open.get("id"));
        long now = Instant.now().getEpochSecond();
        String c = (comment == null || comment.isBlank()) ? null : comment.trim();

        int closed = mapper.closeRequest(id, "CANCELLED", now);
        if (closed == 0) {
            throw new IllegalStateException("이미 처리된 요청입니다.");
        }
        mapper.insertActivity(id, actorId, "CANCELLED", c, now);
        lifecycle.changeStatus(fileId, actorId, "DRAFT", c);
        notifications.notifyStakeholders(fileId, "요청 취소",
                actorId + "님이 승인 요청을 취소했습니다.", actorId);
        return getState(fileId);
    }
}
