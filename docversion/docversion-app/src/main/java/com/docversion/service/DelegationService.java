package com.docversion.service;

import com.docversion.mapper.DelegationMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 승인 위임 (RD-SRS-9.7 확장, 4-C).
 *
 * <p>승인자가 부재일 때 기간을 정해 대리인에게 승인 권한을 넘긴다.
 * <ul>
 *   <li>위임은 권한의 <b>추가</b>이지 박탈이 아니다 — 위임자 본인도 계속 판정 가능.</li>
 *   <li>위임자당 활성 위임 1건 (DB active_marker UNIQUE가 최종 보장).</li>
 *   <li>자기 자신에게 위임 불가, 기간은 최소 오늘 이상.</li>
 *   <li>대리 판정의 실제 처리자는 approval_request_approvers.acted_by에 남는다.</li>
 * </ul>
 */
@Service
public class DelegationService {

    private final DelegationMapper mapper;

    public DelegationService(DelegationMapper mapper) {
        this.mapper = mapper;
    }

    /** 내 위임 현황: 내가 건 활성 위임(mine) + 내게 온 활성 위임 목록(received). */
    public Map<String, Object> state(String userId) {
        return Map.of(
                "mine", orEmpty(mapper.findActiveByDelegator(userId)),
                "received", mapper.findActiveForDelegate(userId));
    }

    private static Map<String, Object> orEmpty(Map<String, Object> m) {
        return m == null ? Map.of() : m;
    }

    /**
     * 위임 설정: 지금부터 days일 동안 delegateId에게.
     * 이미 활성 위임이 있으면 거부(먼저 해지하도록 — 갱신 의도를 명시적으로).
     */
    @Transactional
    public Map<String, Object> delegate(String delegatorId, String delegateId, int days) {
        if (delegateId == null || delegateId.isBlank()) {
            throw new InvalidRequestException("대리인을 지정해야 합니다.");
        }
        String delegate = delegateId.trim();
        if (delegate.equals(delegatorId)) {
            throw new InvalidRequestException("자기 자신에게는 위임할 수 없습니다.");
        }
        if (days < 1 || days > 365) {
            throw new InvalidRequestException("위임 기간은 1~365일이어야 합니다.");
        }
        long now = Instant.now().getEpochSecond();
        long ends = now + (long) days * 86400;
        try {
            mapper.insertDelegation(delegatorId, delegate, now, ends, now);
        } catch (DuplicateKeyException e) {
            throw new WorkflowConflictException("이미 활성 위임이 있습니다. 기존 위임을 해지한 뒤 다시 설정하세요.");
        }
        return state(delegatorId);
    }

    /** 위임 해지 — 위임자 본인. */
    @Transactional
    public Map<String, Object> revoke(String delegatorId) {
        int n = mapper.revoke(delegatorId, Instant.now().getEpochSecond());
        if (n == 0) {
            throw new WorkflowConflictException("해지할 활성 위임이 없습니다.");
        }
        return state(delegatorId);
    }

    /** 판정 시점 기준, actor가 지금 대리할 수 있는 위임자 목록. (ApprovalService에서 사용) */
    public List<String> currentDelegators(String actorId) {
        return mapper.findCurrentDelegators(actorId, Instant.now().getEpochSecond());
    }
}
