package com.docversion.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 승인 워크플로 (RD-SRS-9.7) 데이터 접근 — 다중 승인자 (V8, 4-A).
 *
 * <p>요청 1건 : 승인자 N명(approval_request_approvers). 판정 방식은 요청의 mode.
 * "문서당 열린 요청 하나"는 open_marker UNIQUE로 계속 보장한다.
 */
@Mapper
public interface ApprovalMapper {

    /** 승인 요청 1건 생성 (status=OPEN, open_marker=file_id, mode 지정). */
    int insertRequest(@Param("id") String id,
                      @Param("fileId") String fileId,
                      @Param("requesterId") String requesterId,
                      @Param("mode") String mode,
                      @Param("createdAt") long createdAt);

    /** 요청의 승인자 1명 등록 (seq는 입력 순서, 1부터). */
    int insertRequestApprover(@Param("requestId") String requestId,
                              @Param("approverId") String approverId,
                              @Param("seq") int seq);

    /** 문서의 현재 열린(OPEN) 요청 조회. 없으면 null. */
    Map<String, Object> findOpenByFile(@Param("fileId") String fileId);

    /** 07/12 - C-1: OPEN 요청 행 FOR UPDATE 잠금 조회 (판정·번복·취소 직렬화용). */
    Map<String, Object> findOpenByFileForUpdate(@Param("fileId") String fileId);

    /** 요청의 승인자 목록 (seq 오름차순): approverId, seq, decision, decidedAt, comment, actedBy. */
    List<Map<String, Object>> listApprovers(@Param("requestId") String requestId);

    /**
     * 승인자 개인 판정 기록. PENDING인 본인 행만 갱신 (이미 판정했으면 0 반환).
     * actedBy: 실제 처리자(본인. 위임 시 대리인 — 4-C).
     */
    int decideApprover(@Param("requestId") String requestId,
                       @Param("approverId") String approverId,
                       @Param("decision") String decision,
                       @Param("decidedAt") long decidedAt,
                       @Param("comment") String comment,
                       @Param("actedBy") String actedBy);

    /**
     * 요청을 닫는다(최종 판정). status를 APPROVED/REJECTED/CANCELLED로,
     * open_marker는 NULL로(다음 요청 가능하게). OPEN인 것만 닫히도록 조건.
     * @return 영향 행 수(1이면 성공, 0이면 이미 닫혀 있었음)
     */
    int closeRequest(@Param("id") String id,
                     @Param("status") String status,
                     @Param("decidedAt") long decidedAt);

    /**
     * 판정 번복 (4-D): 열린(OPEN) 요청에서 해당 승인자의 판정을 PENDING으로 되돌린다.
     * JOIN으로 요청이 여전히 OPEN인 경우에만 갱신 — 번복과 확정이 동시에 일어나는
     * 경합에서 "확정된 요청의 자식 행이 PENDING으로 바뀌는" 모순을 DB 수준에서 차단.
     * @return 1=번복됨, 0=이미 확정됐거나 판정 전
     */
    int retractApprover(@Param("requestId") String requestId,
                        @Param("approverId") String approverId);

    /** 판정 이력 1건 기록. */
    int insertActivity(@Param("requestId") String requestId,
                       @Param("actorId") String actorId,
                       @Param("action") String action,
                       @Param("comment") String comment,
                       @Param("timestamp") long timestamp);

    /**
     * 문서의 승인 요청 이력 목록(최신순). 승인 현황 요약 포함:
     * approvedCount / rejectedCount / totalApprovers (자식 테이블 집계).
     */
    List<Map<String, Object>> listByFile(@Param("fileId") String fileId);
}
