package com.docversion.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 보존 정책 (RD-SRS-9.10) 데이터 접근.
 */
@Mapper
public interface RetentionMapper {

    // ---------- 정책 CRUD ----------
    int insertPolicy(@Param("id") String id,
                     @Param("scopeType") String scopeType,
                     @Param("scopeId") String scopeId,
                     @Param("minDays") int minDays,
                     @Param("maxDays") int maxDays,
                     @Param("maxVersions") int maxVersions,
                     @Param("autoCleanup") int autoCleanup,
                     @Param("createdAt") long createdAt);

    Map<String, Object> getPolicy(@Param("id") String id);

    List<Map<String, Object>> listPolicies(@Param("activeOnly") boolean activeOnly);

    /** 스케줄러 자동 정리 대상(활성 + auto_cleanup=1). */
    List<Map<String, Object>> listAutoCleanupPolicies();

    int updatePolicy(@Param("id") String id,
                     @Param("minDays") int minDays,
                     @Param("maxDays") int maxDays,
                     @Param("maxVersions") int maxVersions,
                     @Param("autoCleanup") int autoCleanup,
                     @Param("updatedAt") long updatedAt);

    int deactivatePolicy(@Param("id") String id, @Param("updatedAt") long updatedAt);

    // ---------- 범위 해석: 정책이 적용될 파일 목록 ----------
    List<String> filesGlobal();

    List<String> filesByOwner(@Param("ownerUserId") String ownerUserId);

    List<String> filesByFolderPrefix(@Param("prefix") String prefix);

    int fileExists(@Param("fileId") String fileId);

    // ---------- 버전 정리 ----------
    String currentVersionId(@Param("fileId") String fileId);

    /** 파일의 모든 버전(최신순). version_id, revision_no, timestamp, storage_key. */
    List<Map<String, Object>> listVersions(@Param("fileId") String fileId);

    int deleteVersion(@Param("versionId") String versionId);

    /** 해당 버전이 등장하는 diff 캐시 제거(외래키/정합성). */
    int deleteDiffsForVersion(@Param("fileId") String fileId, @Param("versionId") String versionId);

    /**
     * 해당 버전의 DLP 검사 이력 제거. (RD-SRS-5.1 · 5.2 ↔ 9.10 접점)
     *
     * <p>V15가 만든 {@code fk_scans_version}에는 {@code ON DELETE} 절이 없다.
     * MariaDB 기본값은 RESTRICT이므로, 검사 행이 남아 있는 동안에는
     * {@link #deleteVersion(String)}이 제약 위반으로 실패하고 정리 트랜잭션 전체가 롤백된다.
     * 즉 V15 이후 업로드된 버전(= 검사가 한 번이라도 돌아간 모든 버전)은
     * 보존 정책이 삭제할 수 없는 상태였다.
     *
     * <p>버전보다 <b>먼저</b> 호출해야 한다. {@code dlp_findings}는
     * {@code fk_findings_scan}이 {@code ON DELETE CASCADE}이므로 함께 사라진다.
     *
     * @return 삭제된 검사 행 수(감사 기록용)
     */
    int deleteScansForVersion(@Param("versionId") String versionId);
}
