package com.docversion.service;

import com.docversion.mapper.RetentionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 보존 정리의 DB 삭제 트랜잭션 담당 (RD-SRS-9.10).
 *
 * <p>한 파일에서 정리 대상으로 선별된 버전들의 (diff 캐시 + 버전 행) 삭제와 감사 로그 기록을
 * 하나의 트랜잭션으로 묶는다. 실제 파일(스토리지) 삭제는 트랜잭션 밖에서 별도로 수행한다
 * (롤백 불가 자원이므로). DocumentVersionService/VersionWriteService 분리와 동일한 패턴.
 *
 * <p>이력 기록은 AuditLogService로 일원화(목표 간극(나)). 이전의 자체 기록 형식
 * (subject 무접두, affecteduser 공백, object_type "document")은 통일 형식
 * ("file_" 접두, 행위자, "files")으로 정규화되었다 — activity는 현재 쓰기 전용이라 호환 영향 없음.
 */
@Service
public class RetentionPurgeService {

    private final RetentionMapper mapper;
    private final AuditLogService audit;

    public RetentionPurgeService(RetentionMapper mapper, AuditLogService audit) {
        this.mapper = mapper;
        this.audit = audit;
    }

    /**
     * 선별된 버전들을 DB에서 삭제하고 1건의 감사 로그를 남긴다.
     *
     * <p><b>삭제 순서가 계약이다.</b> files_versions를 참조하는 자식 행을 모두 먼저 지운 뒤에
     * 버전을 지운다. 순서를 바꾸면 외래키 제약에 걸려 이 트랜잭션 전체가 롤백된다.
     *
     * <ol>
     *   <li>{@code version_diffs} — 외래키는 없지만 남겨두면 고아 캐시가 된다</li>
     *   <li>{@code dlp_scans} — V15의 {@code fk_scans_version}에 {@code ON DELETE} 절이 없어
     *       기본값 RESTRICT다. <b>반드시</b> 먼저 지워야 한다. 이 한 줄이 빠져 있어서
     *       V15 이후 검사가 한 번이라도 돌아간 버전은 보존 정책이 삭제할 수 없었다.
     *       {@code dlp_findings}는 {@code ON DELETE CASCADE}로 함께 사라진다</li>
     *   <li>{@code files_versions} — 버전 행 자체</li>
     * </ol>
     *
     * <p>앞으로 files_versions를 참조하는 테이블이 늘어나면 이 메서드에 삭제를 추가해야 한다.
     * 외래키를 RESTRICT로 두는 것은 그 누락을 조용히 넘기지 않고 오류로 드러내기 위함이다.
     */
    @Transactional
    public void purgeFile(String fileId, List<String> versionIds, String actingUser) {
        int scansDeleted = 0;
        for (String vid : versionIds) {
            mapper.deleteDiffsForVersion(fileId, vid);
            scansDeleted += mapper.deleteScansForVersion(vid);
            mapper.deleteVersion(vid);
        }
        // 검사 이력이 함께 사라졌다는 사실을 기록에 남긴다. 건수가 없으면 나중에
        // "판정 기록이 원래 없었다"와 "보존 정리가 지웠다"를 구분할 수 없다.
        audit.record(actingUser, fileId, "retention_deleted", null,
                Map.of("deleted", String.valueOf(versionIds.size()),
                        "dlpScansDeleted", String.valueOf(scansDeleted)));
    }
}
