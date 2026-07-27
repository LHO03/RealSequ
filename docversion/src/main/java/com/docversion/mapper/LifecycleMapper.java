package com.docversion.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 문서 상태 관리 (RD-SRS-9.6) 데이터 접근.
 * 상태 컬럼은 documents에, 변경 이력은 document_status_history에 둔다.
 */
@Mapper
public interface LifecycleMapper {

    /** 현재 상태 조회 (soft delete 제외). 없으면 null. */
    String findStatus(@Param("fileId") String fileId);

    /** 07/12 - C-1: 문서 행 FOR UPDATE 잠금 조회 (활성 트랜잭션 안에서만 의미 있음). */
    String findStatusForUpdate(@Param("fileId") String fileId);

    /** 상태 갱신 + 상태 변경 시각/수정 시각 갱신. */
    int updateStatus(@Param("fileId") String fileId,
                     @Param("status") String status,
                     @Param("updatedAt") long updatedAt);

    /** 상태 변경 이력 1건 기록 (사유 포함). */
    int insertStatusHistory(@Param("fileId") String fileId,
                            @Param("fromStatus") String fromStatus,
                            @Param("toStatus") String toStatus,
                            @Param("changedBy") String changedBy,
                            @Param("reason") String reason,
                            @Param("changedAt") long changedAt);

    /**
     * P1d: 이 문서에 지금까지 쌓인 상태 이력 건수. insertStatusHistory 직후 호출하면
     * 방금 넣은 행의 일련번호가 된다(상태 변경은 findStatusForUpdate 잠금으로 직렬화).
     * 알림 중복 방지 키의 사건 식별자로 쓴다.
     */
    int countStatusHistory(@Param("fileId") String fileId);

    /** 상태 변경 이력 목록 (최신순). */
    List<Map<String, Object>> listStatusHistory(@Param("fileId") String fileId);
}
