package com.docversion.diff;

import com.docversion.diff.DiffTypes.DiffResult;
import com.docversion.domain.FileContent;
import com.docversion.mapper.FilesVersionMapper;
import com.docversion.mapper.VersionDiffMapper;
import com.docversion.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * P1c: diff 계산 백그라운드 워커 (RD-SRS-9.4).
 *
 * <p>리스너가 적재한 PENDING 작업을 주기적으로 폴링하여 계산하고 COMPLETED/FAILED로 전이한다.
 * 요청 스레드에서 diff를 계산하던 과거 구조를 대체 — 업로드 응답이 diff 계산을 기다리지 않고,
 * 계산이 실패해도 재시도(attempts)로 복원된다.
 *
 * <p>멀티 인스턴스 안전:
 * <ul>
 *   <li>{@code claim}은 status=PENDING일 때만 PROCESSING으로 원자 전이 → 두 워커가 같은 작업을
 *       동시에 잡지 못한다(먼저 성공한 쪽만 affected=1).</li>
 *   <li>폴링 시작에 {@code requeueStale}로 오래된 PROCESSING(죽은 워커)을 PENDING으로 회수한다.</li>
 * </ul>
 * (본격적인 분산 락/SKIP LOCKED은 P2 과제. 여기서는 원자 UPDATE 점유 + stale 회수로 충분.)
 */
@Component
public class DiffJobWorker {

    private static final Logger log = LoggerFactory.getLogger(DiffJobWorker.class);

    /** 재시도 한계. 초과 시 FAILED 확정. */
    private static final int MAX_ATTEMPTS = 3;
    /** 이 시간(초)보다 오래 PROCESSING인 작업은 죽은 워커로 보고 회수. */
    private static final long STALE_SECONDS = 120;
    /** 1회 폴링 처리량. */
    private static final int BATCH = 50;

    private final VersionDiffMapper diffs;
    private final FilesVersionMapper versions;
    private final StorageService storage;
    private final DiffService diffService;

    public DiffJobWorker(VersionDiffMapper diffs, FilesVersionMapper versions,
                         StorageService storage, DiffService diffService) {
        this.diffs = diffs;
        this.versions = versions;
        this.storage = storage;
        this.diffService = diffService;
    }

    // 주기 실행(테스트에서는 initial-delay를 크게 잡아 수동 runOnce만 돌린다).
    @Scheduled(fixedDelayString = "${docversion.diff.worker.fixed-delay-ms:15000}",
            initialDelayString = "${docversion.diff.worker.initial-delay-ms:20000}")
    public void poll() {
        int n = runOnce(BATCH);
        if (n > 0) {
            log.info("[diff 워커] {}건 처리", n);
        }
    }

    /**
     * 한 번의 폴링. 오래된 PROCESSING을 회수한 뒤 PENDING을 최대 {@code batch}건 계산한다.
     * @return 실제로 점유해 처리한 작업 수(테스트 확인용).
     */
    public int runOnce(int batch) {
        long now = Instant.now().getEpochSecond();
        diffs.requeueStale(now - STALE_SECONDS, now);

        List<Map<String, Object>> pending = diffs.selectPending(batch);
        int handled = 0;
        for (Map<String, Object> row : pending) {
            long id = ((Number) row.get("id")).longValue();
            // 원자 점유: 다른 워커가 이미 잡았으면 건너뜀.
            if (diffs.claim(id, Instant.now().getEpochSecond()) != 1) {
                continue;
            }
            handled++;
            int attemptNo = ((Number) row.get("attempts")).intValue() + 1; // claim이 +1 함
            process(id,
                    (String) row.get("fileId"),
                    (String) row.get("fromVersionId"),
                    (String) row.get("toVersionId"),
                    attemptNo);
        }
        return handled;
    }

    private void process(long id, String fileId, String fromVersionId, String toVersionId, int attemptNo) {
        try {
            String fromKey = versions.selectStorageKey(fromVersionId);
            String toKey = versions.selectStorageKey(toVersionId);
            if (fromKey == null || toKey == null) {
                throw new IllegalStateException("버전 storage_key 누락 (from=" + fromVersionId
                        + ", to=" + toVersionId + ")");
            }
            String fromMime = versions.selectMimetype(fromVersionId);
            String toMime = versions.selectMimetype(toVersionId);

            FileContent oldContent = new FileContent(storage.readFile(fromKey).data(), fromMime);
            FileContent newContent = new FileContent(storage.readFile(toKey).data(), toMime);
            DiffResult diff = diffService.computeDiff(oldContent, newContent);

            diffs.markCompleted(id, diff.method.toDbValue(), diff.addedLines, diff.deletedLines,
                    diff.summary, diff.unifiedDiff, Instant.now().getEpochSecond());
            log.debug("diff 계산 완료: file={} ({} -> {}) {}", fileId, fromVersionId, toVersionId, diff.summary);
        } catch (Exception e) {
            long now = Instant.now().getEpochSecond();
            String msg = trim(e.getMessage());
            if (attemptNo >= MAX_ATTEMPTS) {
                diffs.markFailed(id, msg, now);
                log.warn("diff 계산 실패 확정(FAILED, {}회): id={} file={} {}", attemptNo, id, fileId, msg);
            } else {
                diffs.requeue(id, msg, now);
                log.info("diff 계산 실패 → 재시도 예약({}회): id={} file={} {}", attemptNo, id, fileId, msg);
            }
        }
    }

    private static String trim(String s) {
        if (s == null) return "unknown";
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
