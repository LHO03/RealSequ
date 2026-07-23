package com.docversion.service;

import com.docversion.domain.VersionInfo;
import com.docversion.mapper.ApprovalMapper;
import com.docversion.mapper.DocumentMapper;
import com.docversion.mapper.FilesVersionMapper;
import com.docversion.mapper.LifecycleMapper;
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
    // V11 클러스터 1: 열린 승인 요청 검사(정책 A)·상태 되돌림에 필요.
    //   순환 의존을 피하려 ApprovalService/DocumentLifecycleService(빈)가 아니라 매퍼를 직접 주입한다
    //   (memory 규약: DocumentLifecycleService에 ApprovalService 대신 ApprovalMapper 주입과 동일 원칙).
    private final ApprovalMapper approvalMapper;
    private final LifecycleMapper lifecycleMapper;

    public VersionWriteService(DocumentMapper documentMapper,
                               FilesVersionMapper filesVersionMapper,
                               AuditLogService audit,
                               NotificationService notifications,
                               ApprovalMapper approvalMapper,
                               LifecycleMapper lifecycleMapper) {
        this.documentMapper = documentMapper;
        this.filesVersionMapper = filesVersionMapper;
        this.audit = audit;
        this.notifications = notifications;
        this.approvalMapper = approvalMapper;
        this.lifecycleMapper = lifecycleMapper;
    }

    /**
     * createInitialVersion DB 단계: documents + files_versions INSERT + 이력 기록.
     * 파일은 이미 호출자가 저장한 상태. 예외 발생 시 전체 롤백 → 호출자가 파일 보상 삭제.
     */
    @Transactional
    public void persistInitialVersion(VersionInfo version, String currentPath) {
        persistInitialVersion(version, currentPath, null);
    }

    /** 07/12 - RD-SRS-9.3: 사용자 입력 변경 사유(reason) 수용 — 없으면 기존 자동 문구. */
    @Transactional
    public void persistInitialVersion(VersionInfo version, String currentPath, String userReason) {
        // 07/12 - I-1: UNIQUE(owner_user_id, path_hash)가 동시 생성 경합의 최종 방어선.
        //   중복이면 여기서 DuplicateKeyException → 호출자(createInitialVersion)가
        //   "이미 생성된 문서에 새 버전 추가"로 폴백한다.
        documentMapper.insertDocument(
                version.getFileId(), version.getUserId(),
                currentPath, sha256Hex(currentPath), currentPath,
                version.getVersionId(), version.getRevisionNo(),
                version.getTimestamp(), version.getTimestamp());

        filesVersionMapper.insertVersion(version);

        String reason = (userReason == null || userReason.isBlank())
                ? "revision_no=1" : userReason.trim();
        setMetadataReason(version.getVersionId(), reason);
        audit.record(version.getUserId(), version.getFileId(),
                "version_created", version.getVersionId(), Map.of("reason", reason));
    }

    /**
     * onDocumentModified DB 단계: FOR UPDATE로 라이브 포인터 잠그고 revision_no를 단조 증가.
     * <p>반환: 확정된 (previousVersionId, newRevisionNo, previousStorageKey).
     * 07/12 - C-3: 문서 없음도 storage_key 누락과 같은 IllegalStateException으로 통일.
     *   (호출자 onDocumentModified가 3-A에서 findOwner로 존재를 이미 확인했으므로,
     *    여기서 문서가 없다는 것은 정합성 이상 — null 반환의 "빈 결과" 경로는 도달
     *    불가능한 죽은 코드였고, 테스트가 그 경로를 기대해 현재 코드와 어긋나 있었다.)
     */
    @Transactional
    public ModifyResult persistModifiedVersion(VersionInfo newVersion) {
        return persistModifiedVersion(newVersion, null);
    }

    /** 07/12 - RD-SRS-9.3: 사용자 입력 변경 사유(reason) 수용 — 없으면 "rev a -> b" 자동 문구. */
    @Transactional
    public ModifyResult persistModifiedVersion(VersionInfo newVersion, String userReason) {
        // FOR UPDATE — 동시 수정 시 같은 revision_no 발급 방지(C++ TODO 해소).
        // UNIQUE(file_id, revision_no)는 최종 방어선, 이 lock이 1차 방어선.
        Map<String, Object> live = documentMapper.findLivePointerForUpdate(newVersion.getFileId());
        if (live == null) {
            // 07/12 - C-3: 소유권 검사를 통과한 뒤 포인터가 없으면 데이터 정합성 이상
            throw new IllegalStateException("문서 라이브 포인터 없음: " + newVersion.getFileId());
        }

        // V11 클러스터 1: 정합성 정책 강제 (documents 행을 이미 잠근 상태 — 규약: documents → approval_requests).
        //   문서 행 잠금이 승인 요청 생성(request의 getStatusForUpdate)과 직렬화되므로, 여기서 열린 요청을
        //   평범히 읽어도 경합 없이 최신 상태를 본다(동시 request는 이 잠금에서 대기).
        String currentStatus = asString(live.get("status"));
        enforceModificationPolicy(newVersion.getFileId(), currentStatus, newVersion.getTimestamp(),
                newVersion.getUserId());

        String previousVersionId = asString(live.get("currentVersionId"));
        long previousRevisionNo = asLong(live.get("currentRevisionNo"));
        long newRevisionNo = previousRevisionNo + 1;

        String previousStorageKey = filesVersionMapper.selectStorageKey(previousVersionId);
        if (previousStorageKey == null || previousStorageKey.isBlank()) {
            // DB 정합성 오류 → 새 버전 생성 중단(C++와 동일 판단)
            throw new IllegalStateException("이전 버전 storage_key 누락: " + previousVersionId);
        }
        // P1: 이전 버전의 실제 MIME (형식이 바뀌어도 이전 파일을 올바른 형식으로 파싱하도록).
        String previousMimeType = filesVersionMapper.selectMimetype(previousVersionId);

        newVersion.setRevisionNo(newRevisionNo);
        filesVersionMapper.insertVersion(newVersion);

        documentMapper.updateLivePointer(newVersion.getFileId(),
                newVersion.getVersionId(), newRevisionNo, newVersion.getTimestamp());

        String changeNote = (userReason == null || userReason.isBlank())
                ? ("rev " + previousRevisionNo + " -> " + newRevisionNo) : userReason.trim();
        setMetadataReason(newVersion.getVersionId(), changeNote);
        audit.record(newVersion.getUserId(), newVersion.getFileId(),
                "version_updated", newVersion.getVersionId(), Map.of(
                        "reason", changeNote,
                        "revisions", previousRevisionNo + "->" + newRevisionNo));

        // RD-SRS-9.9 / 목표 간극(가): 새 버전 업로드를 이해관계자(구독자)에게 알림.
        // 버전 기록과 같은 트랜잭션으로 적재 — 커밋되면 알림도 반드시 함께 확정된다.
        // notifyStakeholders가 행위자(업로더 본인)는 제외하고, 5분 윈도우로 중복도 막는다.
        // (최초 업로드는 이 시점에 구독자가 없어 알림 대상이 없으므로 수정본 경로에만 둔다.)
        notifications.notifyStakeholders(newVersion.getFileId(), "새 버전",
                "새 버전 v" + newRevisionNo + "이(가) 업로드되었습니다.", newVersion.getUserId());

        return new ModifyResult(previousVersionId, previousRevisionNo, newRevisionNo,
                previousStorageKey, previousMimeType);
    }

    /**
     * V11 클러스터 1 — 새 버전 생성 시 상태·승인 정합성 정책 (정책 A). 호출 시점: documents 행 잠금 보유.
     *
     * <ol>
     *   <li>열린 승인 요청이 있으면 → 업로드 거부(ModificationBlockedException → 409).
     *       "검토 중인 버전이 승인자 몰래 바뀌는" 상황을 원천 차단한다.</li>
     *   <li>열린 요청이 없고 현재 상태가 APPROVED면 → APPROVED → REVISION_DRAFT 전환(+상태 이력).
     *       승인받은 것은 이전 버전이므로 새 버전에 "승인" 도장이 남지 않게 되돌린다.</li>
     *   <li>그 외 상태는 버전만 생성하고 상태 불변.</li>
     * </ol>
     *
     * <p>B 이음새: 훗날 "검토 중에도 업로드(supersede)"를 허용하려면, 1)에서 예외를 던지는 대신
     * 기존 요청을 STALE로 종료 + REVISION_DRAFT 전환하는 분기를 <b>이 메서드 한 곳에만</b> 추가한다.
     * 다른 코드는 손대지 않는다.
     */
    private void enforceModificationPolicy(String fileId, String currentStatus, long now, String actorId) {
        // 1) 정책 A: 열린 승인 요청 동안 업로드 차단.
        if (approvalMapper.findOpenByFile(fileId) != null) {
            throw new ModificationBlockedException(
                    "처리 대기 중인 승인 요청이 있어 새 버전을 올릴 수 없습니다. "
                            + "먼저 승인 요청을 취소하거나 결재를 완료하십시오.");
        }
        // 2) 승인된 문서를 수정하면 다시 수정본 초안으로 (APPROVED → REVISION_DRAFT는 전이표상 허용).
        //    상태 컬럼은 잠긴 documents 행에 있으므로 별도 잠금 없이 안전하게 갱신한다.
        if ("APPROVED".equals(currentStatus)) {
            lifecycleMapper.updateStatus(fileId, "REVISION_DRAFT", now);
            lifecycleMapper.insertStatusHistory(fileId, "APPROVED", "REVISION_DRAFT",
                    actorId, "새 버전 업로드로 수정본 초안 전환", now);
        }
    }

    /**
     * RD-SRS-9.3 보조: 버전 metadata에 변경 사유를 JSON_SET. (버전 데이터 관심사라 여기 유지 —
     * activity 이력 기록은 AuditLogService로 이관됨.)
     */
    /** 07/12 - I-1: 경로 해시 (V10의 SHA2(...,256)와 동일한 소문자 16진수). */
    private static String sha256Hex(String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원 JVM", e); // 표준 알고리즘 — 발생 불가
        }
    }

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
                               long newRevisionNo, String previousStorageKey,
                               String previousMimeType) {
    }
}
