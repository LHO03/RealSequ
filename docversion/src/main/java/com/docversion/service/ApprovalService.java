package com.docversion.service;

import com.docversion.mapper.ApprovalMapper;
import com.docversion.mapper.DocumentMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 승인 워크플로 (RD-SRS-9.7) — 다중 승인자 (V8, 4-A).
 *
 * <p>요청 1건에 승인자 N명을 지정하고, 판정 방식(mode)에 따라 전체 결과를 확정한다.
 * <ul>
 *   <li><b>ALL(전원)</b>: 전원 승인 시 승인. 한 명이라도 반려하면 즉시 전체 반려.</li>
 *   <li><b>MAJORITY(과반)</b>: 승인 수가 과반(⌊n/2⌋+1)에 닿으면 승인 확정.
 *       남은 표를 전부 승인해도 과반이 불가능해지면 그 즉시 반려 확정(조기 확정).
 *       동수 가능 상황(짝수)에서는 보수적으로 반려 쪽 — 승인은 반드시 과반 "초과" 동의.</li>
 *   <li><b>SEQUENTIAL(순차)</b>: 결재선. 앞 순번이 승인해야 다음 차례가 오며(차례 강제),
 *       누구든 반려하면 즉시 전체 반려. 차례가 된 승인자에게 대상 지정 알림.</li>
 * </ul>
 *
 * <p>유지되는 규칙: 문서당 열린 요청 하나(open_marker UNIQUE), 요청은 소유자만(3-B),
 * 자기 승인 금지(요청자는 승인자 목록에 포함 불가), 판정은 지정 승인자 본인만(개인별 1회),
 * 승인/반려로 인한 상태 전이는 워크플로 경로(changeStatusAsWorkflow).
 *
 * <p>단일 승인은 "ALL + 승인자 1명"과 동일하므로 별도 분기가 없다(V8에서 기존 데이터 이관).
 */
@Service
public class ApprovalService {

    private final ApprovalMapper mapper;
    private final DocumentLifecycleService lifecycle;
    private final NotificationService notifications;
    private final UuidGenerator uuid;
    private final DocumentMapper documents; // 인증 3단계(3-B): 소유권 검사용
    private final DelegationService delegations; // 4-C: 위임(대리 승인)

    public ApprovalService(ApprovalMapper mapper, DocumentLifecycleService lifecycle,
                           NotificationService notifications, UuidGenerator uuid,
                           DocumentMapper documents, DelegationService delegations) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.notifications = notifications;
        this.uuid = uuid;
        this.documents = documents;
        this.delegations = delegations;
    }

    /** 현재 열린 요청(없으면 null, 있으면 approvers 목록 포함) + 요청 이력. */
    public record ApprovalState(Map<String, Object> open, List<Map<String, Object>> history) {
    }

    public ApprovalState getState(String fileId) {
        Map<String, Object> open = mapper.findOpenByFile(fileId);
        if (open != null) {
            open.put("approvers", mapper.listApprovers(String.valueOf(open.get("id"))));
        }
        return new ApprovalState(open, mapper.listByFile(fileId));
    }

    /**
     * 승인 요청 생성 — 승인자 N명 + 판정 방식.
     * 인증 3단계(3-B): 문서 소유자만 자기 문서의 승인을 요청할 수 있다.
     */
    @Transactional
    public ApprovalState request(String fileId, String requesterId,
                                 List<String> approverIds, String mode, String comment) {
        String owner = documents.findOwner(fileId);
        if (owner == null) {
            throw new ResourceNotFoundException("문서를 찾을 수 없습니다: " + fileId);
        }
        if (!owner.equals(requesterId)) {
            throw new ForbiddenOperationException("문서 소유자만 승인을 요청할 수 있습니다.");
        }

        // 승인자 목록 정리: 공백 제거·중복 제거(입력 순서 유지)·최소 1명·자기 승인 금지
        Set<String> cleaned = new LinkedHashSet<>();
        if (approverIds != null) {
            for (String a : approverIds) {
                if (a != null && !a.isBlank()) {
                    cleaned.add(a.trim());
                }
            }
        }
        if (cleaned.isEmpty()) {
            throw new InvalidRequestException("승인자를 1명 이상 지정해야 합니다.");
        }
        if (cleaned.contains(requesterId)) {
            throw new WorkflowConflictException("자기 자신을 승인자로 지정할 수 없습니다. (자기 승인 금지)");
        }

        // 판정 방식 검증 (4-A: 전원/과반, 4-B: 순차)
        String m = mode == null ? "ALL" : mode.trim().toUpperCase();
        if (!m.equals("ALL") && !m.equals("MAJORITY") && !m.equals("SEQUENTIAL")) {
            throw new InvalidRequestException("판정 방식은 ALL(전원), MAJORITY(과반), SEQUENTIAL(순차) 중 하나여야 합니다.");
        }

        // 검토중 상태에서만 요청 가능
        // 07/12 - C-1: 잠금 조회 — 수동 상태 변경(changeStatus)과 문서 행에서 직렬화되어,
        //   "검사 시점엔 검토중이었는데 커밋 시점엔 초안"인 TOCTOU 경합을 차단한다.
        String status = lifecycle.getStatusForUpdate(fileId).status();
        if (!"UNDER_REVIEW".equals(status)) {
            throw new WorkflowConflictException("검토중 상태에서만 승인 요청을 생성할 수 있습니다. (먼저 문서를 검토 제출하십시오.)");
        }
        if (mapper.findOpenByFile(fileId) != null) {
            throw new WorkflowConflictException("이미 처리 대기 중인 승인 요청이 있습니다.");
        }

        // V11 클러스터 1(P0-1): 요청 생성 시점의 현재 버전을 승인 대상으로 고정한다.
        //   위 getStatusForUpdate가 documents 행을 잠갔으므로 이 읽기는 일관 스냅샷을 본다.
        //   이후 정책 A(열린 요청 동안 업로드 차단)로 이 버전은 확정까지 바뀌지 않는다.
        String targetVersionId = documents.findCurrentVersionId(fileId);
        if (targetVersionId == null || targetVersionId.isBlank()) {
            throw new WorkflowConflictException("현재 버전이 없어 승인 요청을 만들 수 없습니다.");
        }

        long now = Instant.now().getEpochSecond();
        String id = uuid.newId();
        try {
            mapper.insertRequest(id, fileId, requesterId, m, targetVersionId, now);
        } catch (DuplicateKeyException e) {
            // 동시 요청 경합 시 DB의 uq_open_per_file이 두 번째를 차단
            throw new WorkflowConflictException("이미 처리 대기 중인 승인 요청이 있습니다.");
        }
        int seq = 1;
        for (String a : cleaned) {
            mapper.insertRequestApprover(id, a, seq++);
        }
        String c = blankToNull(comment);
        mapper.insertActivity(id, requesterId, "REQUESTED", c, now);

        // 이해관계자 등록 + 알림 (같은 트랜잭션)
        notifications.subscribe(fileId, requesterId);
        for (String a : cleaned) {
            notifications.subscribe(fileId, a);
        }
        notifications.notifyStakeholders(fileId, "승인 요청",
                requesterId + "님이 승인을 요청했습니다. (방식: " + modeLabel(m)
                        + ", 승인자: " + String.join(", ", cleaned) + ")", requesterId);
        if (m.equals("SEQUENTIAL")) {
            // 순차: 첫 번째 승인자에게 "당신 차례" 대상 지정 알림 (4-B)
            String first = cleaned.iterator().next();
            notifications.notifyUser(first, fileId, "승인 차례",
                    "순차 결재의 1번 차례입니다. 승인 또는 반려를 처리해 주세요. (요청자: " + requesterId + ")");
        }
        return getState(fileId);
    }

    /** 승인 처리: 지정 승인자 본인만, 개인별 1회. 방식에 따라 전체 확정. */
    @Transactional
    public ApprovalState approve(String fileId, String actorId, String comment) {
        return decide(fileId, actorId, comment, true);
    }

    /** 반려 처리: 지정 승인자 본인만, 개인별 1회. 방식에 따라 전체 확정. */
    @Transactional
    public ApprovalState reject(String fileId, String actorId, String comment) {
        return decide(fileId, actorId, comment, false);
    }

    private ApprovalState decide(String fileId, String actorId, String comment, boolean approved) {
        // 07/12 - C-1: 진입 잠금 (규약: documents 행 → approval_requests 행 순서).
        //   요청 행 잠금으로 동시 판정이 직렬화되므로, 아래에서 목록을 읽고 메모리에서
        //   집계하는 방식이 안전해진다. (잠금 없이는 두 승인자가 서로를 PENDING으로 본
        //   스냅샷으로 각자 "미확정"을 계산해, 전원이 판정했는데도 요청이 영원히 OPEN으로
        //   남는 결함이 있었다.) 잠금 이후의 일반 SELECT는 이 트랜잭션의 첫 일관 읽기라
        //   직전 커밋까지 반영된 최신 스냅샷을 본다.
        lifecycle.getStatusForUpdate(fileId);
        Map<String, Object> open = mapper.findOpenByFileForUpdate(fileId);
        if (open == null) {
            throw new WorkflowConflictException("처리할 승인 요청이 없습니다.");
        }
        String id = String.valueOf(open.get("id"));
        String modeVal = String.valueOf(open.get("mode"));
        long now = Instant.now().getEpochSecond();
        String c = blankToNull(comment);

        // 승인자 목록을 먼저 읽어 자격·차례를 검사한다 (오류 사유를 정확히 알려주기 위함).
        List<Map<String, Object>> approvers = mapper.listApprovers(id);
        Map<String, Object> mine = approvers.stream()
                .filter(a -> actorId.equals(String.valueOf(a.get("approverId"))))
                .findFirst().orElse(null);

        // 4-C 위임: 본인이 승인자가 아니면, "지금 대리 가능한 위임자" 중 이 요청의 승인자가
        // 있는지 찾는다. 있으면 그 승인자 자격으로 판정하되 acted_by에 실제 처리자를 남긴다.
        String effectiveApprover = actorId; // 판정이 기록될 승인자 (기본: 본인)
        if (mine == null) {
            List<String> myDelegators = delegations.currentDelegators(actorId);
            Map<String, Object> viaDelegation = approvers.stream()
                    .filter(a -> myDelegators.contains(String.valueOf(a.get("approverId"))))
                    .filter(a -> "PENDING".equals(a.get("decision")))
                    .findFirst().orElse(null); // seq 오름차순 — 여러 명 대리 시 앞 순번 우선
            if (viaDelegation == null) {
                throw new WorkflowConflictException("지정된 승인자(또는 그의 대리인)만 처리할 수 있습니다. (승인자: "
                        + joinApprovers(approvers) + ")");
            }
            // 대리 경유 자기 승인 금지: 요청자가 대리인이 되어 자기 요청을 판정하는 우회 차단
            String requester = String.valueOf(open.get("requesterId"));
            if (actorId.equals(requester)) {
                throw new WorkflowConflictException("요청자는 대리인 자격으로도 판정할 수 없습니다. (자기 승인 금지)");
            }
            mine = viaDelegation;
            effectiveApprover = String.valueOf(viaDelegation.get("approverId"));
        }
        if (!"PENDING".equals(mine.get("decision"))) {
            throw new WorkflowConflictException("이미 판정을 완료하셨습니다. (판정은 1회)");
        }
        // 4-B 순차(SEQUENTIAL): 결재선 — 앞 순번이 모두 승인해야 내 차례가 온다.
        // (대리 판정도 원 승인자의 차례를 그대로 따른다)
        if ("SEQUENTIAL".equals(modeVal)) {
            final String eff = effectiveApprover;
            Map<String, Object> current = approvers.stream()
                    .filter(a -> "PENDING".equals(a.get("decision")))
                    .findFirst().orElse(null); // seq 오름차순 정렬이므로 첫 PENDING = 현재 차례
            if (current != null && !eff.equals(String.valueOf(current.get("approverId")))) {
                throw new WorkflowConflictException("순차 결재: 지금은 " + current.get("seq") + "번("
                        + current.get("approverId") + ") 차례입니다. 앞 순번의 판정을 기다려 주세요.");
            }
        }

        // 개인 판정 기록: PENDING인 해당 승인자 행만 갱신 (0행 = 동시 요청 경합 → 이미 판정됨)
        // acted_by = 실제 처리자 — 본인이면 승인자와 동일, 대리면 대리인 ID (감사 추적)
        int updated = mapper.decideApprover(id, effectiveApprover,
                approved ? "APPROVED" : "REJECTED", now, c, actorId);
        if (updated == 0) {
            throw new WorkflowConflictException("이미 판정을 완료하셨습니다. (판정은 1회)");
        }
        String actorLabel = actorId.equals(effectiveApprover)
                ? actorId : effectiveApprover + " (대리: " + actorId + ")";
        mapper.insertActivity(id, actorId, approved ? "APPROVED" : "REJECTED",
                actorId.equals(effectiveApprover) ? c
                        : (c == null ? "" : c + " ") + "[" + effectiveApprover + " 위임 대리]", now);

        // 방금 판정을 로컬 목록에 반영해 집계 (재조회 없이 일관 상태 유지)
        mine.put("decision", approved ? "APPROVED" : "REJECTED");
        int n = approvers.size();
        long ok = approvers.stream().filter(a -> "APPROVED".equals(a.get("decision"))).count();
        long no = approvers.stream().filter(a -> "REJECTED".equals(a.get("decision"))).count();
        Boolean finalApproved = evaluate(modeVal, n, ok, no);

        if (finalApproved == null) {
            // 아직 미확정 — 진행 상황 알림
            notifications.notifyStakeholders(fileId, "승인 진행",
                    actorLabel + "님이 " + (approved ? "승인" : "반려") + "했습니다. (승인 "
                            + ok + " / 반려 " + no + " / 전체 " + n + ")", actorId);
            // 순차: 다음 순번에게 "당신 차례" 알림 (승인으로 줄이 넘어간 경우)
            if ("SEQUENTIAL".equals(modeVal) && approved) {
                approvers.stream()
                        .filter(a -> "PENDING".equals(a.get("decision")))
                        .findFirst()
                        .ifPresent(next -> notifications.notifyUser(
                                String.valueOf(next.get("approverId")), fileId, "승인 차례",
                                "순차 결재의 " + next.get("seq") + "번 차례가 되었습니다. 승인 또는 반려를 처리해 주세요."));
            }
            return getState(fileId);
        }

        // V11 클러스터 1(P0-1) 이중 방어: 승인 확정 직전, 승인 대상 버전이 여전히 현재 버전인지 확인.
        //   정책 A 하에선 열린 요청 동안 업로드가 막혀 정상 경로로는 불일치가 없지만, 수동 경로·경합·
        //   데이터 이상에 대비한 방어선이다. 불일치면 승인하지 않고 요청을 STALE로 종료한다.
        //   (반려 확정은 문서를 어차피 DRAFT로 되돌리므로 버전 불일치가 무해 — 승인 확정만 가드한다.)
        if (finalApproved) {
            String targetVersionId = str(open.get("targetVersionId"));
            String currentVersionId = documents.findCurrentVersionId(fileId);
            if (targetVersionId != null && !targetVersionId.equals(currentVersionId)) {
                int staled = mapper.closeRequest(id, "STALE", now);
                if (staled == 0) {
                    throw new WorkflowConflictException("이미 처리된 요청입니다.");
                }
                mapper.insertActivity(id, actorId, "STALE",
                        "승인 대상 버전이 변경되어 요청을 무효화했습니다. (대상 " + targetVersionId
                                + " / 현재 " + currentVersionId + ")", now);
                notifications.notifyStakeholders(fileId, "요청 무효화",
                        "문서가 변경되어 승인 요청이 무효화되었습니다. 최신 버전으로 다시 요청해 주십시오.", actorId);
                return getState(fileId);
            }
        }

        // 확정: 요청 닫기 + 최종 이력 + 상태 전이 + 결과 알림
        int closed = mapper.closeRequest(id, finalApproved ? "APPROVED" : "REJECTED", now);
        if (closed == 0) {
            throw new WorkflowConflictException("이미 처리된 요청입니다.");
        }
        String summary = "최종 " + (finalApproved ? "승인" : "반려")
                + " (방식 " + modeLabel(modeVal) + ", 승인 " + ok + "/" + n + ")";
        mapper.insertActivity(id, actorId, "CLOSED", summary, now);
        // 9.6 상태 전이: 승인→APPROVED, 반려→DRAFT (워크플로 경로 — 소유권 검사 없음이 정당)
        lifecycle.changeStatusAsWorkflow(fileId, actorId,
                finalApproved ? "APPROVED" : "DRAFT", summary);
        notifications.notifyStakeholders(fileId, finalApproved ? "승인됨" : "반려됨",
                summary + " — 마지막 판정: " + actorLabel, actorId);
        return getState(fileId);
    }

    /**
     * 판정 방식 평가. 반환: true=승인 확정, false=반려 확정, null=미확정(계속 대기).
     * MAJORITY: needed = ⌊n/2⌋+1. 승인 ≥ needed → 승인. 남은 표 전부 승인해도
     * needed 미달(n - 반려 < needed) → 반려. 동수 종착은 승인 불가 → 반려.
     * SEQUENTIAL: 판정 규칙 자체는 전원과 동일(전원 승인 시 승인, 반려 1건이면 반려).
     * 순차의 차이는 "누가 지금 처리할 수 있는가"(차례 강제)이며 decide()에서 검사한다.
     */
    private Boolean evaluate(String mode, int n, long ok, long no) {
        if ("MAJORITY".equals(mode)) {
            int needed = n / 2 + 1;
            if (ok >= needed) return Boolean.TRUE;
            if (n - no < needed) return Boolean.FALSE;
            return null;
        }
        // ALL(전원)·SEQUENTIAL(순차): 반려 1건이면 즉시 반려, 전원 승인 시 승인
        if (no >= 1) return Boolean.FALSE;
        if (ok == n) return Boolean.TRUE;
        return null;
    }

    /**
     * 판정 번복 (4-D): 요청이 아직 열려(OPEN) 있는 동안, 내가 내린 판정을 되돌린다.
     *
     * <p>경계: <b>확정 전만</b>. 확정 후에는 상태 전이·이력·알림(이메일 발송 포함)이 이미
     * 퍼져 있어 되감을 수 없다 — 그때의 정정은 새 승인 요청(전진 정정)으로 한다.
     * <p>번복 가능자: 그 판정 행의 승인자 본인, 또는 그 판정을 실제로 내린 대리인(acted_by).
     * <p>순차(SEQUENTIAL) 무결성: 내 뒤 순번이 이미 판정했다면 번복 불가 — 결재선은
     * 앞 판정 위에 쌓이므로 중간을 빼면 뒤 판정의 전제가 무너진다.
     * <p>번복 사실 자체는 RETRACTED 이력으로 남긴다(감사 추적 — "물렀다"도 기록).
     */
    @Transactional
    public ApprovalState retract(String fileId, String actorId, String comment) {
        // 07/12 - C-1: 진입 잠금 (documents → approval_requests) — 판정과 번복의 직렬화.
        //   잠금이 없으면 "B가 집계하는 사이 A가 번복"하는 교차로, A가 PENDING인데
        //   요청이 APPROVED로 확정되는 역방향 불일치가 가능했다.
        lifecycle.getStatusForUpdate(fileId);
        Map<String, Object> open = mapper.findOpenByFileForUpdate(fileId);
        if (open == null) {
            throw new WorkflowConflictException("번복할 수 있는 열린 요청이 없습니다. (확정된 판정은 번복 불가 — 새 승인 요청으로 정정하세요)");
        }
        String id = String.valueOf(open.get("id"));
        List<Map<String, Object>> approvers = mapper.listApprovers(id);

        // 내가 번복할 수 있는 행: 승인자 본인이거나, 내가 대리로 판정한 행(acted_by)
        Map<String, Object> target = approvers.stream()
                .filter(a -> !"PENDING".equals(a.get("decision")))
                .filter(a -> actorId.equals(String.valueOf(a.get("approverId")))
                        || actorId.equals(String.valueOf(a.get("actedBy"))))
                .findFirst().orElse(null);
        if (target == null) {
            throw new WorkflowConflictException("번복할 내 판정이 없습니다.");
        }
        String effectiveApprover = String.valueOf(target.get("approverId"));

        // 순차: 내 뒤 순번이 이미 판정했다면 번복 불가
        if ("SEQUENTIAL".equals(String.valueOf(open.get("mode")))) {
            int mySeq = ((Number) target.get("seq")).intValue();
            boolean laterDecided = approvers.stream()
                    .anyMatch(a -> ((Number) a.get("seq")).intValue() > mySeq
                            && !"PENDING".equals(a.get("decision")));
            if (laterDecided) {
                throw new WorkflowConflictException("뒤 순번이 이미 판정하여 번복할 수 없습니다. (결재선 무결성)");
            }
        }

        int n = mapper.retractApprover(id, effectiveApprover);
        if (n == 0) {
            // OPEN 검사와 UPDATE 사이에 다른 판정으로 확정된 경합
            throw new WorkflowConflictException("요청이 방금 확정되어 번복할 수 없습니다.");
        }
        long now = Instant.now().getEpochSecond();
        String c = blankToNull(comment);
        String actorLabel = actorId.equals(effectiveApprover)
                ? actorId : effectiveApprover + " (대리: " + actorId + ")";
        mapper.insertActivity(id, actorId, "RETRACTED",
                (c == null ? "" : c + " ") + "[" + effectiveApprover + " 판정 번복]", now);
        notifications.notifyStakeholders(fileId, "판정 번복",
                actorLabel + "님이 판정을 번복했습니다. 해당 승인자는 다시 판정 대기 상태입니다.", actorId);
        return getState(fileId);
    }

    /** 요청 취소: 요청자만. 문서 → DRAFT (검토를 접고 초안으로 되돌림). */
    @Transactional
    public ApprovalState cancel(String fileId, String actorId, String comment) {
        // 07/12 - C-1: 진입 잠금 (documents → approval_requests) — 판정 확정과 취소의 직렬화.
        lifecycle.getStatusForUpdate(fileId);
        Map<String, Object> open = mapper.findOpenByFileForUpdate(fileId);
        if (open == null) {
            throw new WorkflowConflictException("취소할 승인 요청이 없습니다.");
        }
        String requester = String.valueOf(open.get("requesterId"));
        if (!requester.equals(actorId)) {
            throw new WorkflowConflictException("요청자(" + requester + ")만 취소할 수 있습니다.");
        }
        String id = String.valueOf(open.get("id"));
        long now = Instant.now().getEpochSecond();
        String c = blankToNull(comment);

        int closed = mapper.closeRequest(id, "CANCELLED", now);
        if (closed == 0) {
            throw new WorkflowConflictException("이미 처리된 요청입니다.");
        }
        // 이미 이뤄진 개인 판정 기록은 CANCELLED 요청 아래 그대로 보존(감사 추적)
        mapper.insertActivity(id, actorId, "CANCELLED", c, now);
        lifecycle.changeStatusAsWorkflow(fileId, actorId, "DRAFT", c);
        notifications.notifyStakeholders(fileId, "요청 취소",
                actorId + "님이 승인 요청을 취소했습니다.", actorId);
        return getState(fileId);
    }

    // ---------- 헬퍼 ----------

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** Map에서 꺼낸 값을 문자열로 — null은 "null"이 아니라 진짜 null로 보존(버전 비교용). */
    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String modeLabel(String m) {
        return switch (m) {
            case "MAJORITY" -> "과반";
            case "SEQUENTIAL" -> "순차";
            default -> "전원";
        };
    }

    private static String joinApprovers(List<Map<String, Object>> approvers) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> a : approvers) {
            names.add(String.valueOf(a.get("approverId")));
        }
        return String.join(", ", names);
    }
}
