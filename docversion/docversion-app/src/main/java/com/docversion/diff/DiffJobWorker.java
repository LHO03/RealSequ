package com.docversion.diff;

import com.docversion.diff.DiffTypes.DiffResult;
import com.docversion.domain.FileContent;
import com.docversion.mapper.FilesVersionMapper;
import com.docversion.dlp.UnifiedDiffLines;
import com.docversion.mapper.DlpScanMapper;
import com.docversion.mapper.VersionDiffMapper;
import com.docversion.storage.StorageService;
import com.docversion.text.VersionTextService;
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
    // 08/18 - 추출 텍스트 캐시. 비교와 5.x 검사가 같은 결과를 공유한다.
    private final VersionTextService versionText;
    // 08/19 - RD-SRS-5.2 변경분 검사 적재. 비교가 끝나야 변경분이 생기므로 여기서 쌓는다.
    private final DlpScanMapper dlpScans;

    public DiffJobWorker(VersionDiffMapper diffs, FilesVersionMapper versions,
                         StorageService storage, DiffService diffService,
                         VersionTextService versionText, DlpScanMapper dlpScans) {
        this.diffs = diffs;
        this.versions = versions;
        this.storage = storage;
        this.diffService = diffService;
        this.versionText = versionText;
        this.dlpScans = dlpScans;
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

            // 08/18 - 추출 텍스트 재사용. 버전 하나가 여러 비교에 등장하므로
            //   매번 파싱하면 같은 문서를 두 번 이상 처리하게 된다.
            //   처음 필요할 때 추출해 저장하고 이후에는 읽어 쓴다.
            //   확보 실패 시 null이 넘어가며, DiffService가 기존과 동일하게 직접 추출한다.
            String oldText = versionText.getOrExtract(fromVersionId, oldContent);
            String newText = versionText.getOrExtract(toVersionId, newContent);

            DiffResult diff = diffService.computeDiff(oldContent, newContent, oldText, newText);

            diffs.markCompleted(id, diff.method.toDbValue(), diff.addedLines, diff.deletedLines,
                    diff.summary, diff.unifiedDiff, Instant.now().getEpochSecond());
            log.debug("diff 계산 완료: file={} ({} -> {}) {}", fileId, fromVersionId, toVersionId, diff.summary);

            // 08/19 - RD-SRS-5.2: 변경분 검사 적재.
            //   비교 결과가 나와야 변경분이 존재하므로 여기가 적재 지점이다.
            //   추가된 줄이 없으면(삭제만 있거나 바이너리 해시 비교) 검사할 대상이 없다.
            enqueueDeltaScan(fileId, toVersionId, diff.unifiedDiff);
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

    /**
     * 변경분 검사 적재(RD-SRS-5.2).
     *
     * <p>전체 검사(FULL)는 버전 생성 직후 이미 적재되어 있다. 여기서는 그와 별개로
     * "이번 수정으로 민감 데이터가 새로 들어왔는가"에 답하는 작업을 추가한다.
     * 두 검사는 다른 질문에 답하므로 결과를 각각 보관한다.
     *
     * <p>적재 실패가 비교 결과를 되돌려서는 안 되므로 예외를 삼킨다.
     */
    private void enqueueDeltaScan(String fileId, String toVersionId, String unifiedDiff) {
        try {
            if (!UnifiedDiffLines.hasAddedLines(unifiedDiff)) {
                return;   // 추가된 줄이 없으면 검사 대상이 없다
            }
            dlpScans.insertPending(fileId, toVersionId, "DELTA", Instant.now().getEpochSecond());
            log.debug("DLP 변경분 검사 적재(PENDING/DELTA): file={} version={}", fileId, toVersionId);
        } catch (Exception e) {
            log.warn("DLP 변경분 검사 적재 실패(비교 결과에는 영향 없음): file={}", fileId, e);
        }
    }

    private static String trim(String s) {
        if (s == null) return "unknown";
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
