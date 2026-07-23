package com.docversion.event;

import com.docversion.mapper.VersionDiffMapper;
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
 * <p>P1c: diff는 여기서 <b>동기 계산하지 않고</b> PENDING 작업만 적재한다. 실제 계산은
 * {@link com.docversion.diff.DiffJobWorker}가 백그라운드에서 수행하고 COMPLETED/FAILED로 전이한다.
 * (과거엔 이 리스너가 요청 스레드에서 파일을 읽어 동기 계산했고, 실패하면 예외를 삼켜
 *  캐시가 영영 비었다 — 재시도·가시성이 없었다.)
 */
@Component
public class VersionLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(VersionLifecycleListener.class);

    private final VersionDiffMapper versionDiffMapper;

    public VersionLifecycleListener(VersionDiffMapper versionDiffMapper) {
        this.versionDiffMapper = versionDiffMapper;
    }

    // fallbackExecution=true: 이벤트가 트랜잭션 밖에서 발행돼도(쓰기 트랜잭션 종료 후 발행) 실행한다.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onVersionCreated(VersionEvents.VersionCreated event) {
        // 최초 버전: 이전 버전이 없으므로 diff 없음. 알림 seam만.
        notifyStakeholders(event.fileId(), "version_created",
                "Initial version created by " + event.userId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onVersionUpdated(VersionEvents.VersionUpdated event) {
        // 10. diff 작업 적재(PENDING). 워커가 이후 계산한다. 실패해도 버전은 이미 확정.
        try {
            versionDiffMapper.insertPending(
                    event.fileId(), event.fromVersionId(), event.toVersionId(),
                    Instant.now().getEpochSecond());
            log.debug("diff 작업 적재(PENDING): {} ({} -> {})",
                    event.fileId(), event.previousRevisionNo(), event.newRevisionNo());
        } catch (Exception e) {
            log.warn("diff 작업 적재 실패(버전 생성에는 영향 없음): file={}", event.fileId(), e);
        }

        // 12. 알림 seam
        notifyStakeholders(event.fileId(), "version_updated",
                "New revision " + event.newRevisionNo() + " by " + event.userId());
    }

    /** 알림 발송 seam(현재는 로그). Nextcloud 알림 인프라가 꽂힐 자리. */
    private void notifyStakeholders(String fileId, String eventType, String message) {
        log.info("[notify-stub] file={} event={} msg={}", fileId, eventType, message);
    }
}
