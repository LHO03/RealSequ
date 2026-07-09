package com.docversion.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 승인 위임 (RD-SRS-9.7 확장, 4-C) 데이터 접근.
 * 위임자당 활성 위임 1건은 active_marker UNIQUE로 DB가 보장한다.
 */
@Mapper
public interface DelegationMapper {

    /** 위임 설정 (활성). 이미 활성 위임이 있으면 uq_active_per_delegator 위반(예외). */
    int insertDelegation(@Param("delegatorId") String delegatorId,
                         @Param("delegateId") String delegateId,
                         @Param("startsAt") long startsAt,
                         @Param("endsAt") long endsAt,
                         @Param("createdAt") long createdAt);

    /** 내가 건 활성 위임 (없으면 null). 기간 검사 없이 활성 여부만 — 표시용. */
    Map<String, Object> findActiveByDelegator(@Param("delegatorId") String delegatorId);

    /** 내게 온 활성 위임 목록 — 표시용. */
    List<Map<String, Object>> findActiveForDelegate(@Param("delegateId") String delegateId);

    /**
     * 판정 시점 기준: actorId가 "지금" 대리할 수 있는 위임자 ID 목록.
     * 활성 + 기간(starts_at ≤ now ≤ ends_at) 안인 것만.
     */
    List<String> findCurrentDelegators(@Param("delegateId") String delegateId,
                                       @Param("now") long now);

    /** 위임 해지 — 위임자 본인만. 활성인 것만 해지(0행이면 활성 위임 없음). */
    int revoke(@Param("delegatorId") String delegatorId,
               @Param("revokedAt") long revokedAt);

    /** 만료된 활성 위임 일괄 비활성 (스케줄러). @return 처리 건수 */
    int deactivateExpired(@Param("now") long now);
}
