package com.docversion.service;

import com.docversion.domain.VersionInfo;
import com.docversion.mapper.DocumentMapper;
import com.docversion.mapper.FilesVersionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 버전 생명주기의 DB 쓰기 코어. C++ TransactionGuard로 묶여 있던 단위를 {@code @Transactional}로 대체.
 * <p>별도 빈으로 분리한 이유: 같은 빈 내부 호출(self-invocation)은 Spring 프록시를 거치지 않아
 * {@code @Transactional}이 적용되지 않는다. 오케스트레이터(DocumentVersionService)가 이 빈을
 * 호출해야 트랜잭션 경계가 정상 동작한다. 또한 파일 I/O를 트랜잭션 밖에 두어 lock 보유 시간을 최소화한다.
 * <p>logDocumentChangeHistory(activity INSERT + metadata JSON_SET)를 같은 트랜잭션에 포함시켜,
 * C++가 한계로 지적했던 "이력 기록의 트랜잭션 부재"를 해소했다.
 */
@Service
public class VersionWriteService {

    private final DocumentMapper documentMapper;
    private final FilesVersionMapper filesVersionMapper;
    private final AuditLogService audit; // 목표 간극(나): 이력 기록은 AuditLogService로 일원화
    private final NotificationService notifications; // 목표 간극(가): 업로드 시 이해관계자 알림

    public VersionWriteService(DocumentMapper documentMapper,
                               FilesVersionMapper filesVersionMapper,
                               AuditLogService audit,
                               NotificationService notifications) {
        this.documentMapper = documentMapper;
        this.filesVersionMapper = filesVersionMapper;
        this.audit = audit;
        this.notifications = notifications;
    }

    /**
     * createInitialVersion DB 단계: documents + files_versions INSERT + 이력 기록.
     * 파일은 이미 호출자가 저장한 상태. 예외 발생 시 전체 롤백 → 호출자가 파일 보상 삭제.
     */
    @Transactional
    public void persistInitialVersion(VersionInfo version, String currentPath) {
        documentMapper.insertDocument(
                version.getFileId(), version.getUserId(),
                currentPath, currentPath,
                version.getVersionId(), version.getRevisionNo(),
                version.getTimestamp(), version.getTimestamp());

        filesVersionMapper.insertVersion(version);

        setMetadataReason(version.getVersionId(), "revision_no=1");
        audit.record(version.getUserId(), version.getFileId(),
                "version_created", version.getVersionId(), Map.of("reason", "revision_no=1"));
    }

    /**
     * onDocumentModified DB 단계: FOR UPDATE로 라이브 포인터 잠그고 revision_no를 단조 증가.
     * <p>반환: 확정된 (previousVersionId, newRevisionNo, previousStorageKey).
     * 문서 없음/이전 storage_key 누락은 null 반환(호출자가 실패 처리 + 롤백).
     */
    @Transactional
    public ModifyResult persistModifiedVersion(VersionInfo newVersion) {
        // FOR UPDATE — 동시 수정 시 같은 revision_no 발급 방지(C++ TODO 해소).
        // UNIQUE(file_id, revision_no)는 최종 방어선, 이 lock이 1차 방어선.
        Map<String, Object> live = documentMapper.findLivePointerForUpdate(newVersion.getFileId());
        if (live == null) {
            return null; // 문서 없음 → 호출자가 실패 로깅 + 롤백
        }

        String previousVersionId = asString(live.get("currentVersionId"));
        long previousRevisionNo = asLong(live.get("currentRevisionNo"));
        long newRevisionNo = previousRevisionNo + 1;

        String previousStorageKey = filesVersionMapper.selectStorageKey(previousVersionId);
        if (previousStorageKey == null || previousStorageKey.isBlank()) {
            // DB 정합성 오류 → 새 버전 생성 중단(C++와 동일 판단)
            throw new IllegalStateException("이전 버전 storage_key 누락: " + previousVersionId);
        }

        newVersion.setRevisionNo(newRevisionNo);
        filesVersionMapper.insertVersion(newVersion);

        documentMapper.updateLivePointer(newVersion.getFileId(),
                newVersion.getVersionId(), newRevisionNo, newVersion.getTimestamp());

        String changeNote = "rev " + previousRevisionNo + " -> " + newRevisionNo;
        setMetadataReason(newVersion.getVersionId(), changeNote);
        audit.record(newVersion.getUserId(), newVersion.getFileId(),
                "version_updated", newVersion.getVersionId(), Map.of("reason", changeNote));

        // RD-SRS-9.9 / 목표 간극(가): 새 버전 업로드를 이해관계자(구독자)에게 알림.
        // 버전 기록과 같은 트랜잭션으로 적재 — 커밋되면 알림도 반드시 함께 확정된다.
        // notifyStakeholders가 행위자(업로더 본인)는 제외하고, 5분 윈도우로 중복도 막는다.
        // (최초 업로드는 이 시점에 구독자가 없어 알림 대상이 없으므로 수정본 경로에만 둔다.)
        notifications.notifyStakeholders(newVersion.getFileId(), "새 버전",
                "새 버전 v" + newRevisionNo + "이(가) 업로드되었습니다.", newVersion.getUserId());

        return new ModifyResult(previousVersionId, previousRevisionNo, newRevisionNo, previousStorageKey);
    }

    /**
     * RD-SRS-9.3 보조: 버전 metadata에 변경 사유를 JSON_SET. (버전 데이터 관심사라 여기 유지 —
     * activity 이력 기록은 AuditLogService로 이관됨.)
     */
    private void setMetadataReason(String versionId, String reason) {
        if (reason != null && !reason.isBlank() && versionId != null && !versionId.isBlank()) {
            filesVersionMapper.setMetadataReason(versionId, reason);
        }
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static long asLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }

    /** persistModifiedVersion 결과. */
    public record ModifyResult(String previousVersionId, long previousRevisionNo,
                               long newRevisionNo, String previousStorageKey) {
    }
}
