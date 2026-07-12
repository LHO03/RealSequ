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

    /** 상태 변경 이력 목록 (최신순). */
    List<Map<String, Object>> listStatusHistory(@Param("fileId") String fileId);
}
