package com.docversion.event;

import com.docversion.diff.DiffService;
import com.docversion.diff.DiffTypes.DiffResult;
import com.docversion.domain.FileContent;
import com.docversion.mapper.VersionDiffMapper;
import com.docversion.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

/**
 * 버전 생명주기 커밋 이후 부수효과 처리기.
 * <p>{@code @TransactionalEventListener(AFTER_COMMIT)}: 버전 생성/수정 트랜잭션이 커밋된 뒤에만
 * 실행 → "DB 변경은 성공했는데 부수효과 실패로 전체가 실패처럼 보이는" 문제를 구조적으로 방지.
 * 각 핸들러는 예외를 삼켜(로그만) 버전 생성 성공에 영향을 주지 않는다(C++ 후처리 try/catch 대응).
 * <p>diff 캐시는 여기서 계산/저장한다(C++ onDocumentModified 10단계와 동일하게 후처리).
 * 알림/보존 정책은 후속 슬라이스에서 실제 구현으로 채울 seam(현재 로그 stub).
 */
@Component
public class VersionLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(VersionLifecycleListener.class);

    private final DiffService diffService;
    private final VersionDiffMapper versionDiffMapper;
    private final StorageService storage;

    public VersionLifecycleListener(DiffService diffService,
                                    VersionDiffMapper versionDiffMapper,
                                    StorageService storage) {
        this.diffService = diffService;
        this.versionDiffMapper = versionDiffMapper;
        this.storage = storage;
    }

    // fallbackExecution=true: 이벤트가 트랜잭션 밖에서 발행돼도 리스너를 실행한다.
    // (onDocumentModified는 쓰기 트랜잭션이 끝난 뒤 이벤트를 발행하므로, 이 옵션이 없으면
    //  Spring이 "활성 트랜잭션 없음"으로 리스너를 조용히 건너뛴다 → diff 캐시 미적재 버그)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onVersionCreated(VersionEvents.VersionCreated event) {
        // 최초 버전: 이전 버전이 없으므로 diff 없음. 알림 seam만.
        notifyStakeholders(event.fileId(), "version_created",
                "Initial version created by " + event.userId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onVersionUpdated(VersionEvents.VersionUpdated event) {
        // 10. diff 캐시 계산 및 저장 (실패해도 버전은 이미 확정)
        try {
            FileContent oldContent = withMime(storage.readFile(event.fromStorageKey()), event.fromMimeType());
            FileContent newContent = withMime(storage.readFile(event.toStorageKey()), event.toMimeType());
            DiffResult diff = diffService.computeDiff(oldContent, newContent);

            versionDiffMapper.insertIgnore(
                    event.fileId(), event.fromVersionId(), event.toVersionId(),
                    diff.method.toDbValue(), diff.addedLines, diff.deletedLines,
                    diff.summary, diff.unifiedDiff, Instant.now().getEpochSecond());

            log.debug("diff 캐시 저장: {} ({} -> {}) {}",
                    event.fileId(), event.previousRevisionNo(), event.newRevisionNo(), diff.summary);
        } catch (Exception e) {
            log.warn("diff 캐시 실패(버전 생성에는 영향 없음): file={}", event.fileId(), e);
        }

        // 12. 알림 seam
        notifyStakeholders(event.fileId(), "version_updated",
                "New revision " + event.newRevisionNo() + " by " + event.userId());

        // 11. 보존 정책 seam — 후속 슬라이스(retention)에서 구현
        // applyRetentionPolicy(event.fileId());
    }

    private FileContent withMime(FileContent fc, String mime) {
        return new FileContent(fc.data(), mime);
    }

    /**
     * 알림 발송 seam. 후속 슬라이스에서 notifications/outbox 구현으로 대체.
     * (Nextcloud 알림 인프라가 꽂힐 자리 — 현재는 로그)
     */
    private void notifyStakeholders(String fileId, String eventType, String message) {
        log.info("[notify-stub] file={} event={} msg={}", fileId, eventType, message);
    }
}
