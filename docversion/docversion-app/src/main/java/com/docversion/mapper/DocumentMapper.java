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
     * 반환: {currentVersionId, currentRevisionNo, status} 또는 null(문서 없음).
     * <p>status는 V11 정책 A에서 사용: 잠긴 같은 행에서 상태를 함께 읽어, APPROVED 문서에
     * 새 버전이 올라오면 같은 트랜잭션에서 REVISION_DRAFT로 되돌리기 위함(추가 잠금 불필요).
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
     * 현재 최신 버전 ID 조회 (V11 - 승인 대상 버전 귀속용). soft delete 제외. 없으면 null.
     * <p>승인 요청 생성 시 이 값을 target_version_id로 고정하고, 승인 확정 직전 재확인한다.
     * 호출 전 documents 행을 잠갔다면(getStatusForUpdate 등) 이 읽기는 일관 스냅샷을 본다.
     */
    String findCurrentVersionId(@Param("fileId") String fileId);

    /**
     * 라이브 포인터 갱신 (onDocumentModified): current_version_id/revision_no/updated_at.
     */
    int updateLivePointer(@Param("fileId") String fileId,
                          @Param("currentVersionId") String currentVersionId,
                          @Param("currentRevisionNo") long currentRevisionNo,
                          @Param("updatedAt") long updatedAt);
}
