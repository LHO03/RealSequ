package com.docversion.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

/**
 * version_diffs 매퍼 (RD-SRS-9.4).
 *
 * <p>P1c 이후 이 테이블은 결과 캐시가 아니라 <b>계산 작업 테이블</b>이다.
 * 문서 수정 시 리스너가 PENDING 행을 적재하고, {@link com.docversion.diff.DiffJobWorker}가
 * 점유·계산·전이를 수행한다. 상태는 PENDING → PROCESSING → COMPLETED/FAILED.
 *
 * <p>07/24: 동기 계산 시절의 insertIgnore·findCached를 제거했다(호출부 없음).
 * 적재는 insertPending, 조회는 findByPair가 대신한다.
 */
@Mapper
public interface VersionDiffMapper {

    // ==========================================================
    // P1c: diff 작업 상태 기계 (PENDING → PROCESSING → COMPLETED/FAILED)
    // ==========================================================

    /** 수정 시 PENDING 작업 적재(멱등). 같은 (file,from,to) 쌍이 이미 있으면 INSERT IGNORE로 무시. */
    int insertPending(@Param("fileId") String fileId,
                      @Param("fromVersionId") String fromVersionId,
                      @Param("toVersionId") String toVersionId,
                      @Param("now") long now);

    /** getDiff/재시도용 단건 조회(상태·시도·오류 포함). 없으면 null. */
    Map<String, Object> findByPair(@Param("fileId") String fileId,
                                   @Param("fromVersionId") String fromVersionId,
                                   @Param("toVersionId") String toVersionId);

    /** 죽은 워커가 남긴 오래된 PROCESSING을 PENDING으로 되돌린다(staleBefore 이전 갱신 건). */
    int requeueStale(@Param("staleBefore") long staleBefore, @Param("now") long now);

    /** 처리 대상 PENDING 목록(오래된 것 우선). id/fileId/from/to/attempts 반환. */
    java.util.List<Map<String, Object>> selectPending(@Param("limit") int limit);

    /** 원자적 점유: PENDING인 경우에만 PROCESSING으로 전이(+attempts). 성공 시 1 반환(멀티 인스턴스 경합 방지). */
    int claim(@Param("id") long id, @Param("now") long now);

    /** 계산 완료: 결과 적재 + COMPLETED. */
    int markCompleted(@Param("id") long id,
                      @Param("diffMethod") String diffMethod,
                      @Param("addedLines") int addedLines,
                      @Param("deletedLines") int deletedLines,
                      @Param("summary") String summary,
                      @Param("hunksJson") String hunksJson,
                      @Param("now") long now);

    /** 재시도 여력 소진: FAILED 확정 + 사유 기록. */
    int markFailed(@Param("id") long id, @Param("error") String error, @Param("now") long now);

    /** 재시도 예약: PENDING으로 되돌리고 사유 기록(attempts는 유지). */
    int requeue(@Param("id") long id, @Param("error") String error, @Param("now") long now);

    /** 수동 재시도(엔드포인트): FAILED 건을 attempts=0으로 초기화하고 PENDING 복귀. 반환=영향 행수. */
    int resetToPending(@Param("fileId") String fileId,
                       @Param("fromVersionId") String fromVersionId,
                       @Param("toVersionId") String toVersionId,
                       @Param("now") long now);
}
