package com.docversion.diff;

import com.docversion.diff.DiffTypes.DiffHunk;
import com.docversion.diff.DiffTypes.DiffLine;
import com.docversion.diff.DiffTypes.DiffLineType;
import com.docversion.diff.DiffTypes.DiffMethod;
import com.docversion.diff.DiffTypes.DiffResult;
import com.docversion.diff.DiffTypes.ExtractionResult;
import com.docversion.diff.DiffTypes.SplitLinesResult;
import com.docversion.domain.FileContent;
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
 */
@Service
public class DiffService {

    private static final int CONTEXT_LINES = 3;       // hunk 전후 컨텍스트
    private static final int BINARY_CHECK_BYTES = 8192;

    private final DocumentTextExtractor textExtractor;

    public DiffService(DocumentTextExtractor textExtractor) {
        this.textExtractor = textExtractor;
    }

    // ==========================================================
    // computeDiff: 핵심 진입점
    // ==========================================================
    public DiffResult computeDiff(FileContent oldContent, FileContent newContent) {
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
            if (textExtractor.canExtract(oldContent.mimeType())
                    || textExtractor.canExtract(newContent.mimeType())) {
                ExtractionResult oldEx = textExtractor.extractText(oldContent);
                ExtractionResult newEx = textExtractor.extractText(newContent);

                // 둘 다 성공해야 텍스트 기반 diff (한쪽 실패 시 잘못된 diff 위험 → HASH_ONLY fallback)
                if (oldEx.success() && newEx.success()) {
                    result.isBinary = true;
                    result.method = DiffMethod.TEXT_EXTRACTED;
                    SplitLinesResult oldLines = splitTextLines(oldEx.text());
                    SplitLinesResult newLines = splitTextLines(newEx.text());
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
        List<DiffLine> diffLines = myersDiff(oldSplit.lines(), newSplit.lines());

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

    // ==========================================================
    // isBinaryContent: 앞 8KB에서 NULL 바이트 탐지 (Git 방식)
    // ==========================================================
    private boolean isBinaryContent(FileContent content) {
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
        int maxD = n + m;

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

        // 전진 탐색
        int offset = maxD;
        int vSize = 2 * maxD + 1;
        int[] v = new int[vSize];
        java.util.Arrays.fill(v, -1);
        v[offset + 1] = 0;

        List<int[]> traces = new ArrayList<>();
        int finalD = -1;

        for (int d = 0; d <= maxD; d++) {
            traces.add(v.clone());
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

        // 역추적
        List<EditEntry> edits = new ArrayList<>();
        int x = n, y = m;
        for (int d = finalD; d > 0; d--) {
            int[] prevV = traces.get(d);
            int k = x - y;
            int prevK;
            if (k == -d || (k != d && prevV[offset + k - 1] < prevV[offset + k + 1])) {
                prevK = k + 1;
            } else {
                prevK = k - 1;
            }
            int prevX = prevV[offset + prevK];
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
