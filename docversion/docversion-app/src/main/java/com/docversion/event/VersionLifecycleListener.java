package com.docversion.event;

import com.docversion.mapper.DlpScanMapper;
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
    // 08/19 - RD-SRS-5.1 검사 작업 적재. diff와 같은 지점에서 같은 방식으로 쌓는다.
    private final DlpScanMapper dlpScanMapper;

    public VersionLifecycleListener(VersionDiffMapper versionDiffMapper,
                                    DlpScanMapper dlpScanMapper) {
        this.versionDiffMapper = versionDiffMapper;
        this.dlpScanMapper = dlpScanMapper;
    }

    // fallbackExecution=true: 이벤트가 트랜잭션 밖에서 발행돼도(쓰기 트랜잭션 종료 후 발행) 실행한다.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onVersionCreated(VersionEvents.VersionCreated event) {
        // 최초 버전: 이전 버전이 없으므로 diff는 없다.
        // 다만 DLP 전체 검사(5.1)는 필요하다. "저장되는 모든 문서"가 대상이므로
        // 최초 버전이라고 건너뛰면 첫 업로드가 검사되지 않은 채 남는다.
        enqueueDlpScan(event.fileId(), event.versionId());

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

        // 11. DLP 전체 검사 적재(5.1). 새 버전 전체를 대상으로 한다.
        enqueueDlpScan(event.fileId(), event.toVersionId());

        // 12. 알림 seam
        notifyStakeholders(event.fileId(), "version_updated",
                "New revision " + event.newRevisionNo() + " by " + event.userId());
    }

    /**
     * DLP 전체 검사 작업 적재(RD-SRS-5.1).
     *
     * <p>diff와 마찬가지로 여기서 계산하지 않고 PENDING만 쌓는다.
     * 실제 검사는 {@link com.docversion.dlp.DlpScanWorker}가 배경에서 수행한다.
     * 적재 실패가 버전 생성에 영향을 주지 않도록 예외를 삼킨다.
     */
    private void enqueueDlpScan(String fileId, String versionId) {
        try {
            dlpScanMapper.insertPending(fileId, versionId, "FULL",
                    Instant.now().getEpochSecond());
            log.debug("DLP 검사 적재(PENDING/FULL): file={} version={}", fileId, versionId);
        } catch (Exception e) {
            log.warn("DLP 검사 적재 실패(버전 생성에는 영향 없음): file={}", fileId, e);
        }
    }

    /** 알림 발송 seam(현재는 로그). Nextcloud 알림 인프라가 꽂힐 자리. */
    private void notifyStakeholders(String fileId, String eventType, String message) {
        log.info("[notify-stub] file={} event={} msg={}", fileId, eventType, message);
    }
}
