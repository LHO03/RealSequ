package com.docversion.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 승인 워크플로 (RD-SRS-9.7) 데이터 접근 — 단일 승인자.
 */
@Mapper
public interface ApprovalMapper {

    /** 승인 요청 1건 생성 (status=OPEN, open_marker=file_id). */
    int insertRequest(@Param("id") String id,
                      @Param("fileId") String fileId,
                      @Param("requesterId") String requesterId,
                      @Param("approverId") String approverId,
                      @Param("createdAt") long createdAt);

    /** 문서의 현재 열린(OPEN) 요청 조회. 없으면 null. */
    Map<String, Object> findOpenByFile(@Param("fileId") String fileId);

    /** 요청 ID로 조회. */
    Map<String, Object> findById(@Param("id") String id);

    /**
     * 요청을 닫는다(판정). status를 APPROVED/REJECTED/CANCELLED로,
     * open_marker는 NULL로(다음 요청 가능하게). OPEN인 것만 닫히도록 조건.
     * @return 영향 행 수(1이면 성공, 0이면 이미 닫혀 있었음)
     */
    int closeRequest(@Param("id") String id,
                     @Param("status") String status,
                     @Param("decidedAt") long decidedAt);

    /** 판정 이력 1건 기록. */
    int insertActivity(@Param("requestId") String requestId,
                       @Param("actorId") String actorId,
                       @Param("action") String action,
                       @Param("comment") String comment,
                       @Param("timestamp") long timestamp);

    /** 문서의 승인 요청 이력 목록(최신순). */
    List<Map<String, Object>> listByFile(@Param("fileId") String fileId);
}
