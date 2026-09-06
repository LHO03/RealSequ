package com.docversion.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * DLP 검사 작업 및 탐지 항목 접근. (RD-SRS-5.1, 5.2, 5.4)
 *
 * <p>상태 기계는 9.4 버전 비교(version_diffs)와 동일하다.
 * 이미 운용 중인 구조이므로 새로 설계하지 않고 복제했다.
 */
@Mapper
public interface DlpScanMapper {

    /**
     * 검사 작업 적재. 같은 버전·같은 범위의 작업이 이미 있으면 아무것도 하지 않는다.
     * (UNIQUE(version_id, scope) 위반을 예외 대신 무시로 처리)
     */
    int insertPending(@Param("fileId") String fileId,
                      @Param("versionId") String versionId,
                      @Param("scope") String scope,
                      @Param("now") long now);

    /** PENDING 작업 조회(오래된 것부터). */
    List<Map<String, Object>> selectPending(@Param("limit") int limit);

    /**
     * 원자적 점유. PENDING 상태일 때만 PROCESSING으로 바꾸고 시도 횟수를 올린다.
     * 여러 워커가 동시에 시도해도 UPDATE가 성공한 하나만 1을 돌려받는다.
     */
    int claim(@Param("id") long id, @Param("now") long now);

    /**
     * 정체 작업 회수. PROCESSING에 오래 머문 작업을 PENDING으로 되돌린다.
     *
     * <p>회수 시 시도 횟수도 함께 올린다. 올리지 않으면 점유 직후 반복적으로
     * 비정상 종료하는 작업이 실패로 확정되지 못하고 무한히 순환한다.
     * (9.x 알림 아웃박스에서 확인된 사항을 반영)
     */
    int requeueStale(@Param("threshold") long threshold, @Param("now") long now);

    /** 판정 완료 기록. */
    int markCompleted(@Param("id") long id,
                      @Param("verdict") String verdict,
                      @Param("totalScore") int totalScore,
                      @Param("threshold") int threshold,
                      @Param("maxSeverity") String maxSeverity,
                      @Param("findingCount") int findingCount,
                      @Param("method") String method,
                      @Param("note") String note,
                      @Param("now") long now);

    /** 재시도 예약(한도 이내). */
    int requeue(@Param("id") long id, @Param("error") String error, @Param("now") long now);

    /** 실패 확정(한도 초과). */
    int markFailed(@Param("id") long id, @Param("error") String error, @Param("now") long now);

    /** 탐지 항목 일괄 저장. */
    int insertFindings(@Param("scanId") long scanId,
                       @Param("findings") List<Map<String, Object>> findings);

    /** 재검사를 위해 기존 탐지 항목 삭제. */
    int deleteFindings(@Param("scanId") long scanId);

    /** 버전·범위별 검사 결과 단건. 없으면 null. */
    Map<String, Object> findByVersionAndScope(@Param("versionId") String versionId,
                                              @Param("scope") String scope);

    /** 검사에 속한 탐지 항목 목록. */
    List<Map<String, Object>> findFindings(@Param("scanId") long scanId);

    /** 문서 단위 검사 이력(최신순). */
    List<Map<String, Object>> findByFile(@Param("fileId") String fileId);

    /** 재검사 요청: 기존 작업을 PENDING으로 되돌린다. */
    int resetToPending(@Param("versionId") String versionId,
                       @Param("scope") String scope,
                       @Param("now") long now);
}
