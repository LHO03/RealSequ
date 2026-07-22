package com.docversion.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

/**
 * documents 테이블 매퍼. C++ DatabaseConnection을 통한 documents 접근의 직역.
 * SQL은 resources/mapper/DocumentMapper.xml에 둔다(기존 prepared statement 보존).
 */
@Mapper
public interface DocumentMapper {

    /** 07/12 - RD-SRS-9.5 열람: 다운로드 파일명 산출용 현재 경로 조회. */
    String findCurrentPath(@Param("fileId") String fileId);

    /**
     * 문서 master INSERT (createInitialVersion).
     */
    int insertDocument(@Param("fileId") String fileId,
                       @Param("ownerUserId") String ownerUserId,
                       @Param("currentPath") String currentPath,
                       @Param("pathHash") String pathHash,
                       @Param("originalName") String originalName,
                       @Param("currentVersionId") String currentVersionId,
                       @Param("currentRevisionNo") long currentRevisionNo,
                       @Param("createdAt") long createdAt,
                       @Param("updatedAt") long updatedAt);

    /**
     * 라이브 포인터 조회 + row lock (onDocumentModified).
     * SELECT ... FOR UPDATE — 트랜잭션 안에서만 lock 유효.
     * 반환: {current_version_id, current_revision_no} 또는 null(문서 없음).
     */
    Map<String, Object> findLivePointerForUpdate(@Param("fileId") String fileId);

    /**
     * (소유자 + 경로)로 기존 문서의 file_id 조회. Nextcloud식 "같은 경로면 같은 문서" 판정용.
     * idx_documents_owner_path 인덱스 활용. 없으면 null.
     */
    String findFileIdByOwnerAndPath(@Param("ownerUserId") String ownerUserId,
                                    @Param("currentPath") String currentPath);

    /**
     * 문서 소유자 조회 (인증 3단계: 소유권 검사용). soft delete 제외. 없으면 null.
     */
    String findOwner(@Param("fileId") String fileId);

    /**
     * 라이브 포인터 갱신 (onDocumentModified): current_version_id/revision_no/updated_at.
     */
    int updateLivePointer(@Param("fileId") String fileId,
                          @Param("currentVersionId") String currentVersionId,
                          @Param("currentRevisionNo") long currentRevisionNo,
                          @Param("updatedAt") long updatedAt);
}
