package com.docversion.diff;

import com.docversion.diff.DiffTypes.DiffHunk;
import com.docversion.diff.DiffTypes.DiffLine;
import com.docversion.diff.DiffTypes.DiffLineType;
import com.docversion.diff.DiffTypes.DiffMethod;
import com.docversion.diff.DiffTypes.DiffResult;
import com.docversion.diff.DiffTypes.ExtractionResult;
import com.docversion.diff.DiffTypes.SplitLinesResult;
import com.docversion.domain.FileContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 버전 간 diff 계산 서비스. C++ Diffservice.h의 DiffService 직역(순수 로직).
 * <p>RD-SRS-9.4: 이전 버전과 현재 버전 간 차이 비교.
 * 흐름: SHA-256 동일성 → 바이너리 체크 → (텍스트 추출 가능 시)Myers / 불가 시 HASH_ONLY.
 *
 * <p><b>규모 한계를 명시적으로 다룬다.</b> Myers 알고리즘은 편집 거리에 비례해
 * 메모리를 쓰므로 입력 규모에 상한이 필요하다. 상한을 넘으면 예외로 죽는 대신
 * HASH_ONLY로 내려간다. "비교 불가"는 정직한 결과이지만 OutOfMemoryError는 아니다.
 * 자세한 근거는 {@link #myersDiff}에 적었다.
 */
@Service
public class DiffService {

    private static final Logger log = LoggerFactory.getLogger(DiffService.class);

    private static final int CONTEXT_LINES = 3;       // hunk 전후 컨텍스트
    private static final int BINARY_CHECK_BYTES = 8192;

    private final DocumentTextExtractor textExtractor;

    /** 줄 단위 비교를 시도할 최대 줄 수(양쪽 합). 넘으면 시도하지 않는다. */
    private final int maxTotalLines;

    /** 추적할 최대 편집 거리. 넘으면 "너무 다르다"로 보고 폴백한다. */
    private final int maxEditDistance;

    public DiffService(DocumentTextExtractor textExtractor,
                       @Value("${docversion.diff.max-total-lines:200000}") int maxTotalLines,
                       @Value("${docversion.diff.max-edit-distance:7000}") int maxEditDistance) {
        this.textExtractor = textExtractor;
        this.maxTotalLines = maxTotalLines;
        this.maxEditDistance = maxEditDistance;
    }

    /**
     * 줄 단위 비교를 포기해야 할 만큼 입력이 큰 경우.
     *
     * <p>메시지는 사용자에게 보이는 요약에 그대로 실린다. 무엇이 한계를 넘었는지
     * 숫자로 남겨 두어야 설정을 조정할지 판단할 수 있다.
     */
    static final class DiffTooLargeException extends RuntimeException {
        DiffTooLargeException(String message) {
            super(message);
        }
    }

    // ==========================================================
    // computeDiff: 핵심 진입점
    // ==========================================================
    public DiffResult computeDiff(FileContent oldContent, FileContent newContent) {
        return computeDiff(oldContent, newContent, null, null);
    }

    /**
     * 08/18 - 미리 추출된 텍스트를 받는 경로. (RD-SRS-9.4)
     *
     * <p>버전 하나는 여러 비교에 등장하므로(v2는 v1↔v2와 v2↔v3에 모두 쓰인다)
     * 매번 파싱하면 같은 문서를 두 번 이상 처리하게 된다. RD-SRS-5.x 검사가
     * 붙으면 세 번이 된다. 호출부가 캐시된 텍스트를 넘겨주면 추출 단계를 건너뛴다.
     *
     * <p>인자가 null이면 기존과 동일하게 이 클래스가 직접 추출한다.
     * 두 인자 모두 null인 2-인자 버전이 기존 동작과 완전히 같다.
     *
     * <p>해시는 여전히 원본 바이트로 계산한다. SHA-256은 파싱보다 훨씬 저렴하므로
     * 비싼 쪽인 텍스트 추출만 줄이는 것이 이 변경의 목적이다.
     *
     * @param oldExtracted 이전 버전의 추출 텍스트. 없으면 null
     * @param newExtracted 이후 버전의 추출 텍스트. 없으면 null
     */
    public DiffResult computeDiff(FileContent oldContent, FileContent newContent,
                                  String oldExtracted, String newExtracted) {
        DiffResult result = new DiffResult();

        // 1. SHA-256 동일성 판별
        String oldHash = computeSHA256(oldContent.data());
        String newHash = computeSHA256(newContent.data());

        if (oldHash.equals(newHash)) {
            boolean binary = isBinaryContent(oldContent) || isBinaryContent(newContent);
            result.isBinary = binary;
            result.method = binary ? DiffMethod.HASH_ONLY : DiffMethod.TEXT_DIRECT;
            result.oldHash = oldHash;
            result.newHash = newHash;
            result.summary = "No changes detected";
            return result;
        }

        // 2. 바이너리 체크
        if (isBinaryContent(oldContent) || isBinaryContent(newContent)) {
            result.oldHash = oldHash;
            result.newHash = newHash;

            // 2-b. 텍스트 추출 가능한 문서 바이너리?
            // 선언된 MIME이 아니라 내용까지 보고 판단한다. 클라이언트가 붙이는
            // Content-Type은 믿을 수 없다(curl은 .docx에 octet-stream을 붙인다).
            if (textExtractor.canExtract(oldContent)
                    || textExtractor.canExtract(newContent)) {

                // 캐시된 텍스트가 있으면 그것을 쓰고, 없으면 직접 추출한다.
                String oldText = oldExtracted;
                String newText = newExtracted;
                if (oldText == null) {
                    ExtractionResult ex = textExtractor.extractText(oldContent);
                    oldText = ex.success() ? ex.text() : null;
                }
                if (newText == null) {
                    ExtractionResult ex = textExtractor.extractText(newContent);
                    newText = ex.success() ? ex.text() : null;
                }

                // 둘 다 성공해야 텍스트 기반 diff (한쪽 실패 시 잘못된 diff 위험 → HASH_ONLY fallback)
                if (oldText != null && newText != null) {
                    result.isBinary = true;
                    result.method = DiffMethod.TEXT_EXTRACTED;
                    SplitLinesResult oldLines = splitTextLines(oldText);
                    SplitLinesResult newLines = splitTextLines(newText);
                    return buildTextDiffResult(result, oldLines, newLines);
                }
            }

            // 순수 바이너리 또는 추출 실패: 해시 비교만
            result.isBinary = true;
            result.method = DiffMethod.HASH_ONLY;
            result.summary = "Binary files differ (SHA-256: "
                    + oldHash.substring(0, 8) + "... -> " + newHash.substring(0, 8) + "...)";
            return result;
        }

        // 2-a. 텍스트 파일: 줄 단위 diff
        result.isBinary = false;
        result.method = DiffMethod.TEXT_DIRECT;
        result.oldHash = oldHash;
        result.newHash = newHash;
        return buildTextDiffResult(result, splitLines(oldContent), splitLines(newContent));
    }

    // ==========================================================
    // buildTextDiffResult: 줄 벡터 → DiffResult 완성 (공통 경로)
    // ==========================================================
    private DiffResult buildTextDiffResult(DiffResult result, SplitLinesResult oldSplit, SplitLinesResult newSplit) {
        List<DiffLine> diffLines;
        try {
            diffLines = myersDiff(oldSplit.lines(), newSplit.lines());
        } catch (DiffTooLargeException e) {
            // 규모 한계를 넘었다. 예외를 밖으로 던지면 비교 작업이 FAILED가 되고
            // 그 버전은 화면에서 "비교 실패"로만 남는다. 해시 비교는 여전히 유효하므로
            // 알 수 있는 만큼은 돌려주는 편이 낫다.
            return degradeToHashOnly(result, e.getMessage());
        }

        int added = 0, deleted = 0;
        for (DiffLine line : diffLines) {
            if (line.type() == DiffLineType.ADDED) added++;
            else if (line.type() == DiffLineType.DELETED) deleted++;
        }
        result.addedLines = added;
        result.deletedLines = deleted;
        result.oldTrailingNewline = oldSplit.hasTrailingNewline();
        result.newTrailingNewline = newSplit.hasTrailingNewline();
        result.hunks = groupIntoHunks(diffLines);
        result.unifiedDiff = formatUnifiedDiff(result.hunks,
                oldSplit.hasTrailingNewline(), newSplit.hasTrailingNewline(),
                oldSplit.lines().size(), newSplit.lines().size());
        result.summary = generateSummary(result);
        return result;
    }

    /**
     * 줄 단위 비교를 포기하고 해시 비교 결과만 남긴다.
     *
     * <p>추가·삭제 줄 수를 0으로 두는 것은 "변경이 없다"는 뜻이 아니다. 해시가 다르므로
     * 내용은 분명히 달라졌고, 다만 그 차이를 줄 단위로 세지 못했을 뿐이다. 요약 문구가
     * 그 구분을 지고 있으므로 문구를 지우거나 줄이지 말 것.
     *
     * <p>hunks와 unifiedDiff를 비우는 것에는 부수 효과가 하나 있다. 변경분 검사(5.2)는
     * unified diff에서 추가된 줄을 뽑아 입력으로 쓰므로, 비어 있으면 DELTA 검사가
     * 적재되지 않는다. 이는 의도한 동작이다. 전체 검사(5.1)는 버전 본문 전체를 대상으로
     * 하므로 그대로 수행되며, 민감 데이터 판별 자체에는 공백이 생기지 않는다.
     */
    private DiffResult degradeToHashOnly(DiffResult result, String reason) {
        log.info("[비교] 줄 단위 비교 생략 — {} (해시 비교로 대체)", reason);
        result.method = DiffMethod.HASH_ONLY;
        result.addedLines = 0;
        result.deletedLines = 0;
        result.hunks = new ArrayList<>();
        result.unifiedDiff = "";
        result.summary = "내용이 달라졌으나 줄 단위 비교는 생략했습니다 — " + reason
                + " (SHA-256: " + shortHash(result.oldHash) + "... -> " + shortHash(result.newHash) + "...)";
        return result;
    }

    private static String shortHash(String hash) {
        if (hash == null || hash.length() < 8) {
            return hash == null ? "" : hash;
        }
        return hash.substring(0, 8);
    }

    // ==========================================================
    // isBinaryContent: 앞 8KB에서 NULL 바이트 탐지 (Git 방식)
    // ==========================================================
    /**
     * 08/18 - static public으로 공개. VersionTextService가 같은 판정을 써야 하기 때문이다.
     * 두 곳이 다른 기준으로 바이너리를 판단하면, 비교는 텍스트로 처리하는데
     * 추출은 미지원으로 기록하는 식의 불일치가 생긴다. 정의는 한 곳에만 둔다.
     */
    public static boolean isBinaryContent(FileContent content) {
        byte[] data = content.data();
        int checkSize = Math.min(data.length, BINARY_CHECK_BYTES);
        for (int i = 0; i < checkSize; i++) {
            if (data[i] == 0x00) return true;
        }
        return false;
    }

    // ==========================================================
    // splitLines / splitTextLines: 줄 분할 + 후행 개행 추적 (\n, \r\n, \r)
    // ==========================================================
    private SplitLinesResult splitLines(FileContent content) {
        return splitTextLines(new String(content.data(), StandardCharsets.UTF_8));
    }

    private SplitLinesResult splitTextLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text.isEmpty()) {
            return new SplitLinesResult(lines, true); // 빈 파일은 개행 있는 것으로 취급
        }
        StringBuilder cur = new StringBuilder();
        boolean trailingNewline;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                lines.add(cur.toString());
                cur.setLength(0);
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
            } else if (c == '\n') {
                lines.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            lines.add(cur.toString());
            trailingNewline = false; // 개행 없이 끝남
        } else {
            trailingNewline = true;  // 개행으로 끝남
        }
        return new SplitLinesResult(lines, trailingNewline);
    }

    // ==========================================================
    // myersDiff: Myers diff (Myers, 1986). 편집 그래프 최단 경로.
    //   시간 O(ND), x이동=DELETE, y이동=INSERT, 대각선=EQUAL
    // ==========================================================
    private enum EditOp { INSERT, DELETE, EQUAL }

    private record EditEntry(EditOp op, int oldIdx, int newIdx) {
    }

    List<DiffLine> myersDiff(List<String> oldLines, List<String> newLines) {
        int n = oldLines.size();
        int m = newLines.size();

        if (n == 0 && m == 0) return new ArrayList<>();
        if (n == 0) {
            List<DiffLine> r = new ArrayList<>();
            for (int j = 0; j < m; j++) {
                r.add(new DiffLine(DiffLineType.ADDED, null, j + 1, newLines.get(j)));
            }
            return r;
        }
        if (m == 0) {
            List<DiffLine> r = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                r.add(new DiffLine(DiffLineType.DELETED, i + 1, null, oldLines.get(i)));
            }
            return r;
        }

        // ------------------------------------------------------
        // 규모 한계 (F1)
        //
        // 여기가 이 클래스에서 유일하게 메모리를 크게 쓰는 곳이다.
        // 아래 루프는 매 단계 v 배열을 통째로 복제해 traces에 쌓으므로,
        // 총 사용량은 (v의 크기) × (편집 거리)에 비례한다.
        //
        // 종전에는 v를 2·(n+m)+1, 즉 **파일 전체 줄 수** 기준으로 잡았다.
        // 그러나 Myers 알고리즘이 실제로 건드리는 대각선은 k ∈ [-d, d]뿐이므로
        // 편집 거리 기준으로 잡으면 충분하다. 이 차이가 결정적이다.
        //
        //   5만 줄 문서에서 500줄 수정 (실측)
        //     종전: 복제 1회 800KB × 1,000단계 = 약 1,012MB
        //     현재: 복제 1회  16KB × 1,000단계 = 약 16MB
        //
        // 같은 계산을 60분의 1의 메모리로 한다. 알고리즘을 바꾼 것이 아니라
        // 배열 크기를 바로잡은 것이다.
        //
        // 그 위에 두 가지 상한을 둔다.
        //   maxTotalLines   — 줄이 지나치게 많으면 시도 자체를 하지 않는다.
        //                     메모리는 아래 상한으로 묶이지만 시간은 O((n+m)·D)이므로
        //                     줄 수가 크면 응답이 하염없이 늘어난다.
        //   maxEditDistance — 여기까지 추적하고도 수렴하지 않으면 "너무 다르다"로 본다.
        //
        // 보관량은 단계마다 그 단계에서 실제로 유효한 구간(2d+1칸)만 남기므로
        // 총합은 4·(D+1)² 바이트다. 기본 7,000이면 최악의 경우 약 196MB이고,
        // 리뷰에서 OOM이 났던 형태(5만 줄 중 3,000줄 수정, D=6,000)는 약 144MB로 완주한다.
        //
        // 두 상한 모두 넘겼을 때 죽지 않고 HASH_ONLY로 내려가는 것이 핵심이다.
        // 상한이 없던 시절에는 5만 줄 중 3,000줄 수정에서 힙 2GB로도 OOM이 났고,
        // 그 OutOfMemoryError는 catch(Exception)을 통과해 작업자를 무한 재시도에
        // 빠뜨렸다. 상한과 폴백은 그 연쇄의 출발점을 없앤다.
        // ------------------------------------------------------
        if ((long) n + m > maxTotalLines) {
            throw new DiffTooLargeException(
                    "줄 수 " + (n + m) + "줄이 한계(" + maxTotalLines + "줄)를 넘습니다");
        }

        // 배열 인덱스가 v[offset + 1]부터 시작하므로 최소 1은 확보한다.
        // 설정값이 0이나 음수로 들어와도 배열 범위를 벗어나지 않게 한다.
        int maxD = Math.max(1, Math.min(n + m, maxEditDistance));

        // 전진 탐색
        int offset = maxD;
        int vSize = 2 * maxD + 1;
        int[] v = new int[vSize];
        java.util.Arrays.fill(v, -1);
        v[offset + 1] = 0;

        List<int[]> traces = new ArrayList<>();
        int finalD = -1;

        for (int d = 0; d <= maxD; d++) {
            // 단계 d에서 유효한 대각선은 k ∈ [-d, d]뿐이다. 배열 전체를 복제하면
            // 아직 채워지지도 않은 칸까지 D번 함께 복사된다. 유효 구간만 남기면
            // 총 보관량이 4·(2·maxD+1)·(D+1)에서 4·(D+1)²로 줄어든다.
            // (기본 상한에서 약 절반)
            traces.add(java.util.Arrays.copyOfRange(v, offset - d, offset + d + 1));
            for (int k = -d; k <= d; k += 2) {
                int x;
                if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
                    x = v[offset + k + 1];        // INSERT
                } else {
                    x = v[offset + k - 1] + 1;    // DELETE
                }
                int y = x - k;
                while (x < n && y < m && oldLines.get(x).equals(newLines.get(y))) {
                    x++;
                    y++;
                }
                v[offset + k] = x;
                if (x >= n && y >= m) {
                    finalD = d;
                    break;
                }
            }
            if (finalD >= 0) break;
        }

        // 상한까지 가고도 경로를 찾지 못했다.
        //
        // 이 검사가 없으면 finalD가 -1로 남고, 아래 역추적 루프가 한 번도 돌지 않은 채
        // 마지막 while이 모든 줄을 EQUAL로 채운다. 즉 "차이 없음"이라는 정반대의 결과가
        // 조용히 나온다. 비교 결과가 틀리는 것은 실패보다 나쁘므로 여기서 끊는다.
        if (finalD < 0) {
            throw new DiffTooLargeException(
                    "편집 거리가 한계(" + maxEditDistance + ")를 넘어 두 버전의 공통 부분을 찾지 못했습니다");
        }

        // 역추적
        List<EditEntry> edits = new ArrayList<>();
        int x = n, y = m;
        for (int d = finalD; d > 0; d--) {
            // 단계 d의 기록은 유효 구간 [-d, d]만 잘라 두었으므로 원본의 offset+k가
            // 여기서는 k+d에 해당한다. 접근하는 k는 언제나 이 범위 안이다 —
            // k == -d와 k == d일 때는 아래 조건이 단락 평가로 배열을 읽지 않고,
            // 그 사이에서는 k±1이 [-d, d]를 벗어나지 않는다.
            int[] prevV = traces.get(d);
            int k = x - y;
            int prevK;
            if (k == -d || (k != d && prevV[k - 1 + d] < prevV[k + 1 + d])) {
                prevK = k + 1;
            } else {
                prevK = k - 1;
            }
            int prevX = prevV[prevK + d];
            int prevY = prevX - prevK;

            while (x > prevX && y > prevY) {
                x--;
                y--;
                edits.add(new EditEntry(EditOp.EQUAL, x, y));
            }
            if (x == prevX && y > prevY) {
                y--;
                edits.add(new EditEntry(EditOp.INSERT, -1, y));
            } else if (y == prevY && x > prevX) {
                x--;
                edits.add(new EditEntry(EditOp.DELETE, x, -1));
            }
        }
        while (x > 0 && y > 0) {
            x--;
            y--;
            edits.add(new EditEntry(EditOp.EQUAL, x, y));
        }
        Collections.reverse(edits);

        List<DiffLine> result = new ArrayList<>();
        for (EditEntry e : edits) {
            switch (e.op()) {
                case EQUAL -> result.add(new DiffLine(DiffLineType.UNCHANGED,
                        e.oldIdx() + 1, e.newIdx() + 1, oldLines.get(e.oldIdx())));
                case DELETE -> result.add(new DiffLine(DiffLineType.DELETED,
                        e.oldIdx() + 1, null, oldLines.get(e.oldIdx())));
                case INSERT -> result.add(new DiffLine(DiffLineType.ADDED,
                        null, e.newIdx() + 1, newLines.get(e.newIdx())));
            }
        }
        return result;
    }

    // ==========================================================
    // groupIntoHunks: 연속 변경을 컨텍스트 3줄과 함께 hunk로 묶음
    // ==========================================================
    private List<DiffHunk> groupIntoHunks(List<DiffLine> diffLines) {
        List<DiffHunk> hunks = new ArrayList<>();
        int nLines = diffLines.size();
        boolean[] isChange = new boolean[nLines];
        for (int i = 0; i < nLines; i++) {
            isChange[i] = diffLines.get(i).type() != DiffLineType.UNCHANGED;
        }

        int i = 0;
        while (i < nLines) {
            if (!isChange[i]) {
                i++;
                continue;
            }
            // 변경 구간 [start, end] 탐색 (컨텍스트 포함, 겹치면 병합)
            int start = Math.max(0, i - CONTEXT_LINES);
            int end = i;
            int j = i;
            while (j < nLines) {
                if (isChange[j]) {
                    end = Math.min(nLines - 1, j + CONTEXT_LINES);
                    j++;
                } else if (j - lastChangeBefore(isChange, j) <= CONTEXT_LINES * 2
                        && hasChangeWithin(isChange, j, CONTEXT_LINES * 2)) {
                    j++;
                } else {
                    break;
                }
            }

            DiffHunk hunk = new DiffHunk();
            int oldStart = -1, newStart = -1, oldCount = 0, newCount = 0;
            for (int p = start; p <= end; p++) {
                DiffLine line = diffLines.get(p);
                hunk.lines.add(line);
                if (line.oldLineNumber() != null) {
                    if (oldStart < 0) oldStart = line.oldLineNumber();
                    oldCount++;
                }
                if (line.newLineNumber() != null) {
                    if (newStart < 0) newStart = line.newLineNumber();
                    newCount++;
                }
            }
            hunk.oldStart = oldStart < 0 ? 0 : oldStart;
            hunk.newStart = newStart < 0 ? 0 : newStart;
            hunk.oldCount = oldCount;
            hunk.newCount = newCount;
            hunks.add(hunk);

            i = end + 1;
        }
        return hunks;
    }

    private int lastChangeBefore(boolean[] isChange, int idx) {
        for (int p = idx - 1; p >= 0; p--) {
            if (isChange[p]) return p;
        }
        return -CONTEXT_LINES * 4;
    }

    private boolean hasChangeWithin(boolean[] isChange, int from, int window) {
        int to = Math.min(isChange.length - 1, from + window);
        for (int p = from; p <= to; p++) {
            if (isChange[p]) return true;
        }
        return false;
    }

    // ==========================================================
    // formatUnifiedDiff: GitHub 스타일 unified diff 문자열
    // ==========================================================
    private String formatUnifiedDiff(List<DiffHunk> hunks,
                                     boolean oldTrailingNewline, boolean newTrailingNewline,
                                     int oldTotal, int newTotal) {
        StringBuilder sb = new StringBuilder();
        for (DiffHunk hunk : hunks) {
            sb.append("@@ -").append(hunk.oldStart).append(',').append(hunk.oldCount)
                    .append(" +").append(hunk.newStart).append(',').append(hunk.newCount)
                    .append(" @@\n");
            for (DiffLine line : hunk.lines) {
                char prefix = switch (line.type()) {
                    case ADDED -> '+';
                    case DELETED -> '-';
                    case UNCHANGED -> ' ';
                };
                sb.append(prefix).append(line.content()).append('\n');
            }
        }
        if (!oldTrailingNewline || !newTrailingNewline) {
            sb.append("\\ No newline at end of file\n");
        }
        return sb.toString();
    }

    // ==========================================================
    // generateSummary: 로그용 요약
    // ==========================================================
    private String generateSummary(DiffResult result) {
        if (result.addedLines == 0 && result.deletedLines == 0) {
            return "No changes detected";
        }
        return result.addedLines + " lines added, " + result.deletedLines + " deleted";
    }

    // ==========================================================
    // computeSHA256: C++ computeSHA256 대응 (hex 소문자)
    // ==========================================================
    String computeSHA256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원 환경", e);
        }
    }
}
