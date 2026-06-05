package com.docversion.service;

import com.docversion.domain.FileContent;
import com.docversion.domain.VersionInfo;
import com.docversion.event.VersionEvents;
import com.docversion.mapper.DocumentMapper;
import com.docversion.mapper.FilesVersionMapper;
import com.docversion.mapper.VersionDiffMapper;
import com.docversion.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 버전 생명주기 오케스트레이터. C++ DocumentVersionWorkflowAPI의 버전 관련 메서드 직역.
 * <ul>
 *   <li>createInitialVersion (RD-SRS-9.1)</li>
 *   <li>onDocumentModified   (RD-SRS-9.2)</li>
 *   <li>getVersionsAtTime    (RD-SRS-9.5)</li>
 * </ul>
 * <p>패턴: 파일 I/O는 트랜잭션 밖(보상 삭제로 정합성 확보), DB 쓰기는 VersionWriteService의
 * {@code @Transactional}, 외부 부수효과(diff 캐시/알림/보존)는 커밋 이후 이벤트로 분리.
 */
@Service
public class DocumentVersionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentVersionService.class);

    private final VersionWriteService writeService;
    private final StorageService storage;
    private final FilesVersionMapper filesVersionMapper;
    private final VersionDiffMapper versionDiffMapper;
    private final DocumentMapper documentMapper;
    private final UuidGenerator uuid;
    private final VersionMetadata metadata;
    private final ApplicationEventPublisher events;
    private final NotificationService notifications;

    public DocumentVersionService(VersionWriteService writeService,
                                  StorageService storage,
                                  FilesVersionMapper filesVersionMapper,
                                  VersionDiffMapper versionDiffMapper,
                                  DocumentMapper documentMapper,
                                  UuidGenerator uuid,
                                  VersionMetadata metadata,
                                  ApplicationEventPublisher events,
                                  NotificationService notifications) {
        this.writeService = writeService;
        this.storage = storage;
        this.filesVersionMapper = filesVersionMapper;
        this.versionDiffMapper = versionDiffMapper;
        this.documentMapper = documentMapper;
        this.uuid = uuid;
        this.metadata = metadata;
        this.events = events;
        this.notifications = notifications;
    }

    // ==========================================================
    // Nextcloud식 업로드: (소유자 + 정규화 경로) 기준 자동 분기
    //   같은 경로면 새 버전(onDocumentModified), 새 경로면 새 문서(createInitialVersion).
    //   경로는 클라이언트 자유 입력이 아니라 서버가 정규화해서 결정한다(보안 + 일관성).
    // ==========================================================
    public UploadOutcome upload(String userId, String folder, String originalName, FileContent content) {
        String path = canonicalPath(userId, folder, originalName);
        String existing = documentMapper.findFileIdByOwnerAndPath(userId, path);
        if (existing != null && !existing.isBlank()) {
            VersionInfo v = onDocumentModified(userId, existing, content);
            return new UploadOutcome(false, path, existing, v);
        }
        VersionInfo v = createInitialVersion(userId, path, content);
        return new UploadOutcome(true, path, v.getFileId(), v);
    }

    /**
     * 서버가 정하는 정규 경로: /{userId}/files/{folder}/{filename}
     * (Nextcloud의 /{user}/files/... 구조를 본뜸). 경로 순회(..)·역슬래시·빈 세그먼트 제거.
     */
    String canonicalPath(String userId, String folder, String originalName) {
        String user = sanitizeSegment(userId, "anonymous");
        String name = baseName(originalName);
        String f = sanitizeFolder(folder);
        StringBuilder sb = new StringBuilder("/").append(user).append("/files");
        if (!f.isEmpty()) sb.append('/').append(f);
        sb.append('/').append(name);
        return sb.toString();
    }

    private String baseName(String original) {
        if (original == null) return "untitled";
        String s = original.replace('\\', '/');
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        s = s.trim();
        return s.isEmpty() ? "untitled" : s;
    }

    private String sanitizeFolder(String folder) {
        if (folder == null || folder.isBlank()) return "";
        String[] parts = folder.replace('\\', '/').split("/");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            String seg = p.trim();
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) continue; // 순회 차단
            if (sb.length() > 0) sb.append('/');
            sb.append(seg);
        }
        return sb.toString();
    }

    private String sanitizeSegment(String s, String fallback) {
        if (s == null) return fallback;
        String t = s.replace('\\', '/').replace("/", "").trim();
        return t.isEmpty() ? fallback : t;
    }

    /** 업로드 결과: 새 문서 생성 여부 + 정규 경로 + fileId + 버전 정보. */
    public record UploadOutcome(boolean created, String path, String fileId, VersionInfo version) {
    }

    // ==========================================================
    // RD-SRS-9.4: 두 버전 간 diff 조회 (version_diffs 캐시)
    // ==========================================================
    public Map<String, Object> getDiff(String fileId, String fromVersionId, String toVersionId) {
        return versionDiffMapper.findCached(fileId, fromVersionId, toVersionId);
    }

    // ==========================================================
    // RD-SRS-9.1: 최초 버전 생성
    // ==========================================================
    public VersionInfo createInitialVersion(String userId, String filePath, FileContent content) {
        String fileId = uuid.newId();
        String versionId = uuid.newId();
        long revisionNo = 1;
        long timestamp = Instant.now().getEpochSecond();
        String storageKey = "objects/" + fileId + "/versions/" + versionId;

        VersionInfo version = new VersionInfo();
        version.setVersionId(versionId);
        version.setFileId(fileId);
        version.setRevisionNo(revisionNo);
        version.setUserId(userId);
        version.setTimestamp(timestamp);
        version.setSize(content.size());
        version.setMimetype(content.mimeType());
        version.setStorageKey(storageKey);
        version.setMetadata(metadata.buildInitial(userId));

        // 1) 파일 저장 (트랜잭션 밖). 실패 시 즉시 예외.
        storage.writeFile(storageKey, content);

        // 2) DB 트랜잭션. 실패 시 저장된 파일 보상 삭제(C++ 보상 삭제 로직 대응).
        try {
            writeService.persistInitialVersion(version, filePath);
        } catch (RuntimeException e) {
            safeDelete(storageKey);
            throw new VersionOperationException("createInitialVersion DB 저장 실패", e);
        }

        // 3) 커밋 이후 부수효과: 알림 등 (실패해도 버전 생성은 성공)
        events.publishEvent(new VersionEvents.VersionCreated(fileId, versionId, revisionNo, userId));
        // 소유자를 문서 구독자(이해관계자)로 자동 등록 (RD-SRS-9.9). 멱등·비핵심.
        notifications.subscribe(fileId, userId);

        return version;
    }

    // ==========================================================
    // RD-SRS-9.2: 문서 수정 → 새 버전 자동 생성
    // ==========================================================
    public VersionInfo onDocumentModified(String userId, String fileId, FileContent newContent) {
        String versionId = uuid.newId();
        long timestamp = Instant.now().getEpochSecond();
        String storageKey = "objects/" + fileId + "/versions/" + versionId;

        VersionInfo version = new VersionInfo();
        version.setVersionId(versionId);
        version.setFileId(fileId);
        // revisionNo는 트랜잭션 안에서 FOR UPDATE 후 확정됨
        version.setUserId(userId);
        version.setTimestamp(timestamp);
        version.setSize(newContent.size());
        version.setMimetype(newContent.mimeType());
        version.setStorageKey(storageKey);
        version.setMetadata(metadata.buildInitial(userId));

        // 1) 새 콘텐츠 저장 (트랜잭션 밖)
        storage.writeFile(storageKey, newContent);

        // 2) DB 트랜잭션: FOR UPDATE + revision 증가 + INSERT/UPDATE + 이력
        VersionWriteService.ModifyResult result;
        try {
            result = writeService.persistModifiedVersion(version);
        } catch (RuntimeException e) {
            safeDelete(storageKey);
            throw new VersionOperationException("onDocumentModified DB 저장 실패", e);
        }
        if (result == null) {
            // 문서 없음: 저장한 파일 보상 삭제 후 빈 결과
            safeDelete(storageKey);
            log.warn("onDocumentModified: documents에 file_id={} 없음", fileId);
            return new VersionInfo();
        }

        // 3) 커밋 이후 부수효과: diff 캐시 계산/저장 + 알림 (실패해도 버전 생성 성공)
        events.publishEvent(new VersionEvents.VersionUpdated(
                fileId,
                result.previousVersionId(), versionId,
                result.previousStorageKey(), storageKey,
                newContent.mimeType(), newContent.mimeType(),
                result.previousRevisionNo(), result.newRevisionNo(),
                userId, timestamp));

        return version;
    }

    // ==========================================================
    // RD-SRS-9.5: 특정 시점의 버전 목록 조회
    // ==========================================================
    public List<VersionInfo> getVersionsAtTime(String userId, String fileId,
                                               long targetTimestamp, int limit, int offset) {
        // limit/offset 방어 (C++와 동일 범위)
        if (limit < 1) limit = 1;
        else if (limit > 100) limit = 100;
        if (offset < 0) offset = 0;

        List<VersionInfo> versions = new ArrayList<>(
                filesVersionMapper.findAtOrBeforeTimestamp(fileId, targetTimestamp, limit, offset));

        // fallback: 이하 버전이 없으면 이후 가장 오래된 1건
        if (versions.isEmpty() && targetTimestamp > 0) {
            VersionInfo future = filesVersionMapper.findEarliestAfterTimestamp(fileId, targetTimestamp);
            if (future != null) {
                versions.add(future);
            }
        }

        // metadata 파싱(author fallback)
        for (VersionInfo v : versions) {
            var parsed = metadata.parse(v.getMetadata());
            v.setMetadataMap(parsed);
            if (v.getUserId() == null || v.getUserId().isBlank()) {
                v.setUserId(parsed.getOrDefault("author", "unknown"));
            }
        }
        return versions;
    }

    private void safeDelete(String storageKey) {
        try {
            storage.deleteFile(storageKey);
        } catch (RuntimeException ex) {
            log.error("보상 파일 삭제 실패: {}", storageKey, ex);
        }
    }
}
