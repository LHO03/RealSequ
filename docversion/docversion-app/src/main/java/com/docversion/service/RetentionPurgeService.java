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

    /** 선별된 버전들을 DB에서 삭제하고 1건의 감사 로그를 남긴다. */
    @Transactional
    public void purgeFile(String fileId, List<String> versionIds, String actingUser) {
        for (String vid : versionIds) {
            mapper.deleteDiffsForVersion(fileId, vid);
            mapper.deleteVersion(vid);
        }
        audit.record(actingUser, fileId, "retention_deleted", null,
                Map.of("deleted", String.valueOf(versionIds.size())));
    }
}
