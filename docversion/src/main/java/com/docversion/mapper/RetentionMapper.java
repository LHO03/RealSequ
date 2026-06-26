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
}
