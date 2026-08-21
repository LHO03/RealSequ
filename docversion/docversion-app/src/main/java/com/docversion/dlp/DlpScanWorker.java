package com.docversion.dlp;

import com.docversion.dlp.api.Finding;
import com.docversion.dlp.api.ScanRequest;
import com.docversion.dlp.api.ScanResult;
import com.docversion.dlp.api.ScanScope;
import com.docversion.dlp.api.ScanVerdict;
import com.docversion.dlp.api.SensitiveDataScanner;
import com.docversion.domain.FileContent;
import com.docversion.mapper.DlpScanMapper;
import com.docversion.mapper.FilesVersionMapper;
import com.docversion.mapper.VersionDiffMapper;
import com.docversion.storage.StorageService;
import com.docversion.text.VersionTextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DLP 검사 작업자. (RD-SRS-5.1 저장 문서 판별)
 *
 * <p>버전 저장 트랜잭션이 커밋된 뒤 적재된 작업을 배경에서 처리한다.
 * 검사를 업로드 응답 경로에 넣지 않는 이유는 두 가지다.
 *
 * <ul>
 *   <li>명세상 서버의 임무는 판정이지 차단이 아니다(차단은 5.3, 클라이언트 담당).
 *       따라서 검사 결과를 기다렸다가 업로드를 허용/거부할 이유가 없다.</li>
 *   <li>성능평가지표가 100MB 문서의 동시 요청을 전제한다. 텍스트 추출과 탐지를
 *       응답 경로에서 수행하면 평균 응답 2000ms 이하를 충족하기 어렵고,
 *       문서 행의 잠금 보유 시간도 늘어난다.</li>
 * </ul>
 *
 * <p>상태 기계는 9.4 버전 비교 작업자와 동일하다.
 * PENDING → PROCESSING → COMPLETED | FAILED 이며, PROCESSING에 오래 머문
 * 작업은 PENDING으로 회수한다.
 */
@Component
public class DlpScanWorker {

    private static final Logger log = LoggerFactory.getLogger(DlpScanWorker.class);

    /** 한 번의 폴링에서 처리할 최대 작업 수. */
    private static final int BATCH = 20;

    /** 이 시간(초)을 넘겨 PROCESSING에 머문 작업은 워커가 죽은 것으로 보고 회수한다. */
    private static final long STALE_SECONDS = 120;

    /** 재시도 한도. 넘으면 FAILED로 확정하고 자동 재시도하지 않는다. */
    private static final int MAX_ATTEMPTS = 3;

    private final DlpScanMapper scans;
    private final FilesVersionMapper versions;
    private final StorageService storage;
    private final VersionTextService versionText;
    private final SensitiveDataScanner scanner;
    // 08/19 - RD-SRS-5.2: 변경분 검사는 9.4 비교 결과를 입력으로 쓴다.
    private final VersionDiffMapper diffs;

    public DlpScanWorker(DlpScanMapper scans,
                         FilesVersionMapper versions,
                         StorageService storage,
                         VersionTextService versionText,
                         SensitiveDataScanner scanner,
                         VersionDiffMapper diffs) {
        this.scans = scans;
        this.versions = versions;
        this.storage = storage;
        this.versionText = versionText;
        this.scanner = scanner;
        this.diffs = diffs;
    }

    @Scheduled(fixedDelayString = "${docversion.dlp.worker.fixed-delay-ms:15000}",
            initialDelayString = "${docversion.dlp.worker.initial-delay-ms:20000}")
    public void poll() {
        int n = runOnce(BATCH);
        if (n > 0) {
            log.info("[DLP 워커] {}건 처리", n);
        }
    }

    /**
     * 한 번의 폴링. 정체 작업을 회수한 뒤 PENDING을 최대 {@code batch}건 검사한다.
     *
     * @return 실제로 점유해 처리한 작업 수(시험 확인용)
     */
    public int runOnce(int batch) {
        long now = Instant.now().getEpochSecond();
        scans.requeueStale(now - STALE_SECONDS, now);

        List<Map<String, Object>> pending = scans.selectPending(batch);
        int handled = 0;
        for (Map<String, Object> row : pending) {
            long id = ((Number) row.get("id")).longValue();
            // 원자 점유: 다른 워커가 이미 잡았으면 건너뛴다.
            if (scans.claim(id, Instant.now().getEpochSecond()) != 1) {
                continue;
            }
            handled++;
            int attemptNo = ((Number) row.get("attempts")).intValue() + 1;   // claim이 +1 함
            process(id,
                    (String) row.get("fileId"),
                    (String) row.get("versionId"),
                    (String) row.get("scope"),
                    attemptNo);
        }
        return handled;
    }

    private void process(long id, String fileId, String versionId, String scope, int attemptNo) {
        try {
            ScanScope scanScope = scopeOf(scope);
            String mime = versions.selectMimetype(versionId);
            String text;
            String unavailableReason;

            if (scanScope == ScanScope.DELTA) {
                // 변경분 검사(5.2). 9.4 비교가 산출한 unified diff에서 추가된 줄만 추린다.
                // 별도 전처리기를 두지 않고 비교 결과를 그대로 재사용한다.
                Map<String, Object> diff = diffs.findCompletedByToVersion(versionId);
                if (diff == null) {
                    // 비교가 아직 끝나지 않았거나 실패한 경우.
                    // 예외로 처리하면 재시도 한도를 소모하므로, 다음 주기에 다시 보도록 되돌린다.
                    scans.requeue(id, "비교 결과 대기 중", Instant.now().getEpochSecond());
                    return;
                }
                text = UnifiedDiffLines.addedLines(str(diff.get("hunksJson")));
                unavailableReason = "변경분에서 검사할 내용을 찾지 못했습니다";
            } else {
                // 전체 검사(5.1). 버전 본문 전체가 대상이다.
                String storageKey = versions.selectStorageKey(versionId);
                if (storageKey == null) {
                    throw new IllegalStateException("버전 storage_key 누락: " + versionId);
                }
                FileContent content = new FileContent(storage.readFile(storageKey).data(), mime);
                // 추출 텍스트는 9.4 비교와 공유한다. 같은 문서를 두 번 파싱하지 않는다.
                text = versionText.getOrExtract(versionId, content);
                unavailableReason =
                        "텍스트를 확보할 수 없어 판정하지 못했습니다(형식 미지원 또는 추출 실패)";
            }

            ScanResult result;
            if (text == null || text.isBlank()) {
                // 추출 불가를 "민감하지 않음"으로 처리하면 검사되지 않은 문서가
                // 안전한 것으로 표시되어 유출 차단이 무력화된다.
                // 작업 자체는 성공(COMPLETED)이되 판정은 불가(UNDETERMINED)로 남긴다.
                result = ScanResult.undetermined("RULE", unavailableReason);
            } else {
                result = scanner.scan(new ScanRequest(fileId, versionId, text, mime, scanScope));
            }

            saveResult(id, result);
            log.debug("DLP 검사 완료: file={} version={} scope={} verdict={} score={}",
                    fileId, versionId, scope, result.verdict(), result.totalScore());

        } catch (Exception e) {
            long now = Instant.now().getEpochSecond();
            String msg = trim(e.getMessage());
            if (attemptNo >= MAX_ATTEMPTS) {
                scans.markFailed(id, msg, now);
                log.warn("DLP 검사 실패 확정(FAILED, {}회): id={} file={} {}", attemptNo, id, fileId, msg);
            } else {
                scans.requeue(id, msg, now);
                log.info("DLP 검사 실패 → 재시도 예약({}회): id={} file={} {}", attemptNo, id, fileId, msg);
            }
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** 판정과 탐지 항목을 기록한다. */
    private void saveResult(long scanId, ScanResult result) {
        long now = Instant.now().getEpochSecond();

        // 재검사인 경우 기존 항목을 지우고 다시 넣는다.
        scans.deleteFindings(scanId);

        List<Finding> findings = result.findings();
        if (!findings.isEmpty()) {
            List<Map<String, Object>> rows = new ArrayList<>(findings.size());
            for (Finding f : findings) {
                Map<String, Object> m = new HashMap<>();
                m.put("patternName", f.patternName());
                m.put("severity", f.severity().name());
                m.put("score", f.score());
                m.put("verified", f.verified() ? 1 : 0);
                m.put("matchOffset", f.offset());
                m.put("matchLength", f.length());
                m.put("maskedValue", f.maskedValue());
                // 점수 상한(max_hits_scored)이 0이면 모든 항목이 점수에 반영된다.
                m.put("scored", f.score() > 0 ? 1 : 0);
                rows.add(m);
            }
            scans.insertFindings(scanId, rows);
        }

        String maxSeverity = result.highestSeverity() == null
                ? null : result.highestSeverity().name();

        scans.markCompleted(scanId,
                result.verdict().name(),
                result.totalScore(),
                result.threshold(),
                maxSeverity,
                findings.size(),
                result.method(),
                trim(result.note()),
                now);
    }

    /** 알 수 없는 범위 문자열은 전체 검사로 둔다. 판정의 정본이 FULL이기 때문이다. */
    private static ScanScope scopeOf(String s) {
        try {
            return ScanScope.valueOf(s);
        } catch (RuntimeException e) {
            return ScanScope.FULL;
        }
    }

    private static String trim(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    /** 판정이 확정되지 않은 상태인지. 조회 API의 표시 판단에 쓴다. */
    public static boolean isConclusive(String verdict) {
        return verdict != null && !ScanVerdict.UNDETERMINED.name().equals(verdict);
    }
}
