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

    /** P1: 이전 버전의 MIME 타입 조회 (VersionUpdated 이벤트의 fromMimeType 정확 전달용). 없으면 null. */
    String selectMimetype(@Param("versionId") String versionId);

    // ----------------------------------------------------------
    // 08/18 - 추출 텍스트 캐시 (V15 컬럼). RD-SRS-9.4 비교와 5.x 검사가 공유한다.
    //   버전 하나가 여러 비교에 등장하므로 매번 파싱하면 같은 문서를 두세 번 처리하게 된다.
    //   처음 필요할 때 추출해 저장하고 이후에는 읽어 쓴다.
    // ----------------------------------------------------------

    /** 추출 상태(PENDING/EXTRACTED/UNSUPPORTED/FAILED). 버전이 없으면 null. */
    String selectTextStatus(@Param("versionId") String versionId);

    /** 추출 텍스트 저장 위치. 미추출이면 null. */
    String selectTextKey(@Param("versionId") String versionId);

    /** 추출 성공 기록. */
    int updateTextExtracted(@Param("versionId") String versionId,
                            @Param("status") String status,
                            @Param("textKey") String textKey,
                            @Param("textChars") long textChars,
                            @Param("now") long now);

    /**
     * 추출 불가 기록(UNSUPPORTED/FAILED).
     * 이 상태는 "안전"을 뜻하지 않는다. 호출부는 판정 불가로 다뤄야 한다.
     */
    int updateTextFailed(@Param("versionId") String versionId,
                         @Param("status") String status,
                         @Param("error") String error,
                         @Param("now") long now);

    /** 07/12 - RD-SRS-9.5 열람: 콘텐츠 응답에 필요한 버전 메타 단건 (file_id 대조용 포함). */
    java.util.Map<String, Object> findVersionForContent(@Param("versionId") String versionId);

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
