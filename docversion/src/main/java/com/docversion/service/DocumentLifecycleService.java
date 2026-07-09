package com.docversion.service;

import com.docversion.domain.DocumentStatus;
import com.docversion.mapper.DocumentMapper;
import com.docversion.mapper.LifecycleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 문서 상태 관리 (RD-SRS-9.6).
 *   - 상태 전이 규칙(DocumentStatus)을 강제한다. 허용되지 않은 전이는 거부.
 *   - 상태를 바꿀 때마다 변경 이력(누가/언제/무엇을→무엇으로/왜)을 함께 남긴다.
 *   - 상태 갱신과 이력 기록은 하나의 트랜잭션으로 묶는다(둘 다 되거나 둘 다 안 되거나).
 */
@Service
public class DocumentLifecycleService {

    private final LifecycleMapper mapper;
    private final NotificationService notifications;
    private final DocumentMapper documents; // 인증 3단계(3-B): 소유권 검사용

    public DocumentLifecycleService(LifecycleMapper mapper, NotificationService notifications,
                                    DocumentMapper documents) {
        this.mapper = mapper;
        this.notifications = notifications;
        this.documents = documents;
    }

    /** 다음 전이 가능한 상태 1개의 표현 (코드명 + 한글 라벨). */
    public record StatusOption(String name, String label) {
    }

    /** 현재 상태 + 한글 라벨 + 전이 가능한 다음 상태 목록. */
    public record StatusView(String status, String label, List<StatusOption> allowed) {
    }

    /** 현재 상태 조회. */
    public StatusView getStatus(String fileId) {
        String s = mapper.findStatus(fileId);
        if (s == null) {
            throw new IllegalArgumentException("문서를 찾을 수 없습니다: " + fileId);
        }
        return view(DocumentStatus.of(s));
    }

    /**
     * 상태 변경 — 수동 경로 (화면/API에서 직접 호출).
     * 인증 3단계(3-B): 문서 소유자만 자기 문서의 상태를 바꿀 수 있다.
     * (승인 절차가 일으키는 상태 변경은 changeStatusAsWorkflow 사용 — 그 경로는
     *  승인자/요청자 자격을 승인 로직이 이미 검증했으므로 소유권 검사를 걸지 않는다.)
     */
    @Transactional
    public StatusView changeStatus(String fileId, String userId, String targetStatus, String reason) {
        String owner = documents.findOwner(fileId);
        if (owner == null) {
            throw new IllegalArgumentException("문서를 찾을 수 없습니다: " + fileId);
        }
        if (!owner.equals(userId)) {
            throw new ForbiddenOperationException("문서 소유자만 상태를 변경할 수 있습니다.");
        }
        return changeStatusAsWorkflow(fileId, userId, targetStatus, reason);
    }

    /**
     * 상태 변경 — 워크플로 경로 (승인/반려/취소가 내부적으로 호출).
     * 전이 규칙 검증 + 이력 기록 + 알림 적재. 소유권 검사 없음(호출자가 자격 검증 책임).
     */
    @Transactional
    public StatusView changeStatusAsWorkflow(String fileId, String userId, String targetStatus, String reason) {
        String s = mapper.findStatus(fileId);
        if (s == null) {
            throw new IllegalArgumentException("문서를 찾을 수 없습니다: " + fileId);
        }
        DocumentStatus current = DocumentStatus.of(s);
        DocumentStatus target = DocumentStatus.of(targetStatus);

        if (current == target) {
            throw new IllegalStateException("이미 '" + current.label() + "' 상태입니다.");
        }
        if (!current.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "'" + current.label() + "' \u2192 '" + target.label() + "' 전이는 허용되지 않습니다.");
        }

        long now = Instant.now().getEpochSecond();
        mapper.updateStatus(fileId, target.name(), now);
        mapper.insertStatusHistory(fileId, current.name(), target.name(), userId,
                (reason == null || reason.isBlank()) ? null : reason.trim(), now);
        // RD-SRS-9.9: 같은 트랜잭션에서 이해관계자에게 알림 + 아웃박스 적재
        notifications.notifyStakeholders(fileId, "상태 변경",
                "문서 상태가 '" + current.label() + "' \u2192 '" + target.label() + "'(으)로 변경되었습니다.", userId);
        return view(target);
    }

    /** 상태 변경 이력 목록. */
    public List<Map<String, Object>> getStatusHistory(String fileId) {
        return mapper.listStatusHistory(fileId);
    }

    private StatusView view(DocumentStatus current) {
        List<StatusOption> opts = new ArrayList<>();
        for (DocumentStatus t : current.allowedTargets()) {
            opts.add(new StatusOption(t.name(), t.label()));
        }
        return new StatusView(current.name(), current.label(), opts);
    }
}
