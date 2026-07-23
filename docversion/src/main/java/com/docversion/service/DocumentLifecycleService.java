package com.docversion.service;

import com.docversion.domain.DocumentStatus;
import com.docversion.mapper.ApprovalMapper;
import com.docversion.mapper.DocumentMapper;
import com.docversion.mapper.LifecycleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
    private final ApprovalMapper approvals; // 07/12 - C-2: 열린 승인 요청 존재 검사용 (매퍼 참조라 순환 의존 없음)

    public DocumentLifecycleService(LifecycleMapper mapper, NotificationService notifications,
                                    DocumentMapper documents, ApprovalMapper approvals) {
        this.mapper = mapper;
        this.notifications = notifications;
        this.documents = documents;
        this.approvals = approvals;
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
            throw new ResourceNotFoundException("문서를 찾을 수 없습니다: " + fileId);
        }
        return view(DocumentStatus.of(s));
    }

    /**
     * 07/12 - C-1: 문서 행을 잠그면서 상태 조회.
     * 상태 검사와 그에 뒤따르는 변경(승인 요청 생성, 판정, 수동 상태 변경)을 문서 단위로
     * 직렬화하기 위한 진입 잠금이다. 잠금은 트랜잭션이 끝날 때 풀리므로, 반드시 호출자의
     * 활성 트랜잭션 안에서 불러야 한다(MANDATORY — 트랜잭션 없이 부르면 즉시 오류로 알려줌).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StatusView getStatusForUpdate(String fileId) {
        String s = mapper.findStatusForUpdate(fileId);
        if (s == null) {
            throw new ResourceNotFoundException("문서를 찾을 수 없습니다: " + fileId);
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
        // 07/12 - C-1: 문서 행을 먼저 잠근다. 이 잠금이 있어야 아래 C-2 가드(열린 요청 검사)와
        //   승인 요청 생성(request)의 상태 검사가 서로의 커밋 전 상태를 읽는 경합(TOCTOU)을
        //   일으키지 못한다. 잠금 순서 규약: documents → approval_requests.
        getStatusForUpdate(fileId);
        String owner = documents.findOwner(fileId);
        if (owner == null) {
            throw new ResourceNotFoundException("문서를 찾을 수 없습니다: " + fileId);
        }
        if (!owner.equals(userId)) {
            throw new ForbiddenOperationException("문서 소유자만 상태를 변경할 수 있습니다.");
        }
        // V11 클러스터 1(P0-2): "승인" 상태는 수동 경로로 진입할 수 없다. 승인은 반드시
        //   승인 워크플로(지정 승인자들의 실제 판정)를 통해서만 도달해야 한다. 이 차단이 없으면
        //   소유자가 요청도 없이 UNDER_REVIEW → APPROVED로 직접 바꿔 자기 문서를 자가 승인할 수 있다.
        //   (승인 로직은 changeStatusAsWorkflow를 쓰므로 이 차단에 걸리지 않는다.)
        if (DocumentStatus.of(targetStatus) == DocumentStatus.APPROVED) {
            throw new ForbiddenOperationException(
                    "승인 상태는 승인 워크플로를 통해서만 도달할 수 있습니다. 상태를 직접 '승인'으로 바꿀 수 없습니다.");
        }
        // 07/12 - C-2: 열린 승인 요청이 있는 동안 수동 상태 변경을 차단한다.
        //   허용하면 예: UNDER_REVIEW→DRAFT 수동 전환 후, 열린 요청의 승인 확정(DRAFT→APPROVED 불허)·
        //   반려/취소(DRAFT→DRAFT 동일 상태 불허)가 전부 전이 규칙에 막혀 요청을 영원히 닫을 수 없고,
        //   open_marker UNIQUE 때문에 새 요청 생성도 불가능한 교착이 된다.
        //   워크플로 경로(changeStatusAsWorkflow)는 승인 로직이 요청을 닫은 "뒤"에 호출하므로 검사하지 않는다.
        if (approvals.findOpenByFile(fileId) != null) {
            throw new WorkflowConflictException(
                    "처리 대기 중인 승인 요청이 있어 상태를 직접 변경할 수 없습니다. 먼저 승인 요청을 취소하거나 결재를 완료하십시오.");
        }
        return changeStatusAsWorkflow(fileId, userId, targetStatus, reason);
    }

    /**
     * 상태 변경 — 워크플로 경로 (승인/반려/취소가 내부적으로 호출).
     * 전이 규칙 검증 + 이력 기록 + 알림 적재. 소유권 검사 없음(호출자가 자격 검증 책임).
     */
    @Transactional
    public StatusView changeStatusAsWorkflow(String fileId, String userId, String targetStatus, String reason) {
        // 07/12 - C-1: 잠금 조회로 전환 — 전이 검사와 updateStatus 사이의 경합 차단.
        String s = mapper.findStatusForUpdate(fileId);
        if (s == null) {
            throw new ResourceNotFoundException("문서를 찾을 수 없습니다: " + fileId);
        }
        DocumentStatus current = DocumentStatus.of(s);
        DocumentStatus target = DocumentStatus.of(targetStatus);

        if (current == target) {
            throw new WorkflowConflictException("이미 '" + current.label() + "' 상태입니다.");
        }
        if (!current.canTransitionTo(target)) {
            throw new WorkflowConflictException(
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
            // V11 클러스터 1(P0-2): APPROVED는 수동 전이 대상이 아니므로 UI 후보에서 제외한다.
            //   (전이표 자체는 승인 워크플로가 쓰므로 유지하되, 화면에 "직접 승인" 버튼은 노출하지 않는다.)
            if (t == DocumentStatus.APPROVED) {
                continue;
            }
            opts.add(new StatusOption(t.name(), t.label()));
        }
        return new StatusView(current.name(), current.label(), opts);
    }
}
