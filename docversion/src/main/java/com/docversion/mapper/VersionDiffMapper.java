package com.docversion.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

/**
 * version_diffs 캐시 매퍼 (RD-SRS-9.4).
 * onDocumentModified 후처리에서 (이전→새) diff를 INSERT IGNORE로 적재.
 */
@Mapper
public interface VersionDiffMapper {

    /**
     * diff 캐시 적재. INSERT IGNORE — UNIQUE(file_id,from,to) 충돌 시 무시
     * (MariaDB 확정 전제, SQL 그대로 보존). 캐시 실패는 버전 생성에 영향 없음.
     */
    int insertIgnore(@Param("fileId") String fileId,
                     @Param("fromVersionId") String fromVersionId,
                     @Param("toVersionId") String toVersionId,
                     @Param("diffMethod") String diffMethod,
                     @Param("addedLines") int addedLines,
                     @Param("deletedLines") int deletedLines,
                     @Param("summary") String summary,
                     @Param("hunksJson") String hunksJson,
                     @Param("createdAt") long createdAt);

    /** 캐시 단건 조회(UI 직접 조회용). 없으면 null. */
    Map<String, Object> findCached(@Param("fileId") String fileId,
                                   @Param("fromVersionId") String fromVersionId,
                                   @Param("toVersionId") String toVersionId);
}
