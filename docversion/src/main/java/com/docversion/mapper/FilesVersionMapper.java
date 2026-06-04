package com.docversion.mapper;

import com.docversion.domain.VersionInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * files_versions 테이블 매퍼. createInitialVersion / onDocumentModified /
 * getVersionsAtTime / logDocumentChangeHistory(JSON_SET)에서 사용.
 */
@Mapper
public interface FilesVersionMapper {

    /** 버전 INSERT. UNIQUE(file_id, revision_no)가 중복 revision_no 최종 방어선. */
    int insertVersion(VersionInfo version);

    /** version_id로 storage_key 단건 조회 (이전 버전 콘텐츠 위치). */
    String selectStorageKey(@Param("versionId") String versionId);

    /**
     * targetTimestamp 이하 버전 목록(최신순, 페이지네이션).
     * getVersionsAtTime의 1차 조회.
     */
    List<VersionInfo> findAtOrBeforeTimestamp(@Param("fileId") String fileId,
                                              @Param("targetTimestamp") long targetTimestamp,
                                              @Param("limit") int limit,
                                              @Param("offset") int offset);

    /**
     * targetTimestamp 이후 가장 오래된 버전 1건.
     * getVersionsAtTime의 fallback(이하 버전이 하나도 없을 때).
     */
    VersionInfo findEarliestAfterTimestamp(@Param("fileId") String fileId,
                                           @Param("targetTimestamp") long targetTimestamp);

    /**
     * 변경 이유 metadata 갱신. MariaDB JSON_SET 사용(확정 전제 — SQL 그대로 보존).
     * logDocumentChangeHistory에서 versionId가 있을 때만 호출.
     */
    int setMetadataReason(@Param("versionId") String versionId,
                          @Param("reason") String reason);
}
