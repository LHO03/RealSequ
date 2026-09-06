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

    /**
     * 변경분 검사가 비교 결과를 기다릴 수 있는 최대 주기 수. (F7)
     *
     * <p>이 한도가 없으면 비교 결과가 영영 나오지 않는 경우에 검사 작업이 15초마다
     * 무한히 되돌려진다. 실패도 완료도 아닌 채로 남아 작업 목록을 오염시키고,
     * 폴링 로그를 계속 채워 정작 봐야 할 기록을 묻는다.
     *
     * <p>도달 경로는 실제로 있다. 비교가 없는 버전(최초 버전 등)에 재검사 API로
     * {@code scope=DELTA}를 직접 요청하면 대응하는 비교 작업이 아예 생기지 않는다.
     * 비교가 FAILED로 확정된 경우도 마찬가지다.
     *
     * <p>재시도 한도(3)와 구분해 넉넉히 잡는다. 여기서 기다리는 대상은 실패가 아니라
     * 아직 끝나지 않은 선행 작업이며, 큰 문서의 비교는 몇 분이 걸릴 수 있다.
     * 기본 폴링 주기 15초 기준으로 약 5분이다.
     */
    private static final int MAX_DELTA_WAIT_ATTEMPTS = 20;

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
            boolean canContinue = process(id,
                    (String) row.get("fileId"),
                    (String) row.get("versionId"),
                    (String) row.get("scope"),
                    attemptNo);
            if (!canContinue) {
                // 자원 고갈 등 치명적 실패. 같은 주기에서 다음 작업을 이어 잡으면
                // 같은 실패가 연쇄된다. 이번 주기는 접고 다음 주기에 다시 온다.
                log.warn("[DLP 워커] 치명적 실패로 이번 주기를 중단합니다. 남은 작업은 다음 주기에 처리됩니다.");
                break;
            }
        }
        return handled;
    }

    /**
     * 작업 한 건 처리.
     *
     * @return 이번 주기를 계속 진행해도 되면 true, 즉시 중단해야 하면 false
     */
    private boolean process(long id, String fileId, String versionId, String scope, int attemptNo) {
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
                    if (attemptNo >= MAX_DELTA_WAIT_ATTEMPTS) {
                        // 기다릴 만큼 기다렸다. 여기서 끊지 않으면 영원히 되돌려진다.
                        //
                        // FAILED가 아니라 UNDETERMINED로 마무리한다. 작업 자체는 정상적으로
                        // 수행되었고, 다만 검사할 변경분을 확보하지 못했을 뿐이다. 이 구분은
                        // 이 시스템 전반의 원칙이다 — 검사하지 못한 문서를 "민감하지 않음"으로
                        // 표시하면 유출 차단이 무력화된다.
                        //
                        // 나중에 비교가 완료되면 재검사 API로 다시 돌릴 수 있다.
                        saveResult(id, ScanResult.undetermined("RULE",
                                "비교 결과가 나오지 않아 변경분을 검사하지 못했습니다"
                                        + " (약 " + (MAX_DELTA_WAIT_ATTEMPTS / 4) + "분 대기 후 종료)"));
                        log.warn("DLP 변경분 검사 대기 종료(UNDETERMINED): id={} file={} version={} — "
                                        + "대응하는 비교 결과가 COMPLETED 상태로 존재하지 않습니다",
                                id, fileId, versionId);
                        return true;
                    }
                    // 비교가 아직 끝나지 않았거나 실패한 경우.
                    // 예외로 처리하면 재시도 한도를 소모하므로, 다음 주기에 다시 보도록 되돌린다.
                    scans.requeue(id, "비교 결과 대기 중", Instant.now().getEpochSecond());
                    return true;
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
            return true;

        } catch (Exception e) {
            // 회복 가능한 실패: 저장소 일시 오류, 정합성 이상 등. 한도까지 재시도한다.
            long now = Instant.now().getEpochSecond();
            String msg = trim(e.getMessage());
            if (attemptNo >= MAX_ATTEMPTS) {
                scans.markFailed(id, msg, now);
                log.warn("DLP 검사 실패 확정(FAILED, {}회): id={} file={} {}", attemptNo, id, fileId, msg);
            } else {
                scans.requeue(id, msg, now);
                log.info("DLP 검사 실패 → 재시도 예약({}회): id={} file={} {}", attemptNo, id, fileId, msg);
            }
            return true;

        } catch (Throwable t) {
            // Error 계열(OutOfMemoryError, StackOverflowError, NoClassDefFoundError 등).
            //
            // 이 갈래가 없으면 catch(Exception)을 그대로 통과해 작업이 PROCESSING에 남고,
            // 120초 뒤 requeueStale이 회수해 같은 실패를 무한히 반복한다. 반복될 때마다
            // 자원을 다시 고갈시키므로, 그 순간의 업로드나 결재 요청까지 함께 실패한다.
            //
            // 대량 문서를 연속으로 검사할 때(정확도 측정 등) 특히 위험하다. 한 건이
            // 서버 전체를 흔들지 않도록, 시도 횟수와 무관하게 즉시 FAILED로 확정한다.
            failFatal(id, fileId, t);
            return false;
        }
    }

    /**
     * 재시도가 무의미한 실패를 기록한다. 기록 자체가 실패해도 워커를 멈추지 않는다.
     *
     * <p>자원이 고갈된 상태에서는 이 기록마저 실패할 수 있다. 그 경우 작업은 PROCESSING으로
     * 남고 stale 회수가 다시 집게 되는데, 그때는 자원 상황이 달라져 있을 수 있으므로
     * 재시도에 의미가 생긴다.
     */
    private void failFatal(long id, String fileId, Throwable t) {
        String msg = t.getClass().getSimpleName()
                + (t.getMessage() == null ? "" : ": " + t.getMessage());
        try {
            scans.markFailed(id, trim("재시도 없이 실패 확정 — " + msg), Instant.now().getEpochSecond());
        } catch (Throwable ignored) {
            // 기록 실패는 삼킨다. 아래 로그가 유일한 흔적이 된다.
        }
        log.error("DLP 검사 치명적 실패(재시도 없이 FAILED 확정): id={} file={} {}", id, fileId, msg, t);
    }

    /**
     * 탐지 항목을 한 번에 밀어 넣을 최대 행 수. (F12)
     *
     * <p>엔진은 규칙 하나당 최대 10,000건까지 탐지하므로 규칙 5종이면 한 문서에서
     * 50,000행이 나올 수 있다. 그것을 단일 INSERT로 만들면 SQL 문 하나가 수 MB에
     * 이르러 MariaDB의 {@code max_allowed_packet}에 걸린다. 그때 나는 오류는
     * "무엇이 너무 큰지"를 알려주지 않아 원인을 찾기 어렵다.
     *
     * <p>나눠 넣으면 문장 크기가 예측 가능해진다. 1,000행이면 대략 100KB 안쪽이라
     * 기본 설정에서 넉넉하고, 왕복 횟수도 최악의 경우 50회로 부담되지 않는다.
     *
     * <p>같은 트랜잭션 안에서 나누므로 중간에 실패하면 전부 되돌아간다.
     * 탐지 항목이 일부만 남는 상태는 생기지 않는다.
     */
    private static final int FINDING_INSERT_CHUNK = 1_000;

    /** 탐지 항목을 나눠 넣는다. 단일 INSERT가 패킷 상한에 걸리는 것을 막는다. */
    private void insertFindingsInChunks(long scanId, List<Map<String, Object>> rows) {
        for (int from = 0; from < rows.size(); from += FINDING_INSERT_CHUNK) {
            int to = Math.min(from + FINDING_INSERT_CHUNK, rows.size());
            scans.insertFindings(scanId, rows.subList(from, to));
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
            insertFindingsInChunks(scanId, rows);
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
