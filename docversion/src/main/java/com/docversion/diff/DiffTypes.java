package com.docversion.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * DiffService 관련 자료구조. C++ Diffservice.h의 enum/struct 직역.
 */
public final class DiffTypes {

    private DiffTypes() {
    }

    /** 개별 변경 줄의 유형 (GitHub diff의 +/-/컨텍스트). */
    public enum DiffLineType { ADDED, DELETED, UNCHANGED }

    /** computeDiff가 사용한 비교 방식. version_diffs.diff_method 저장용. */
    public enum DiffMethod {
        TEXT_DIRECT,      // 텍스트: 줄 단위 Myers diff
        TEXT_EXTRACTED,   // 문서 바이너리: 텍스트 추출 후 Myers diff
        HASH_ONLY;        // 순수 바이너리: SHA-256 비교만

        /** C++ diffMethodToString 대응. version_diffs.diff_method 컬럼 값. */
        public String toDbValue() {
            return switch (this) {
                case TEXT_DIRECT -> "myers";
                case TEXT_EXTRACTED -> "myers_extracted";
                case HASH_ONLY -> "sha256";
            };
        }

        /** C++ stringToDiffMethod 대응. 알 수 없는 값은 안전 기본값 TEXT_DIRECT. */
        public static DiffMethod fromDbValue(String s) {
            if (s == null) return TEXT_DIRECT;
            return switch (s) {
                case "myers" -> TEXT_DIRECT;
                case "myers_extracted" -> TEXT_EXTRACTED;
                case "sha256" -> HASH_ONLY;
                default -> TEXT_DIRECT;
            };
        }
    }

    /** MIME 기반 문서 유형 분류. */
    public enum DocumentType { TEXT, EXTRACTABLE_BINARY, PURE_BINARY }

    /**
     * 개별 변경 줄. oldLineNumber/newLineNumber는 "해당 없음"을 null로 표현
     * (C++ std::optional&lt;int&gt; → Integer null).
     */
    public record DiffLine(DiffLineType type, Integer oldLineNumber, Integer newLineNumber, String content) {
    }

    /** 변경 블록(hunk). GitHub의 "@@ -a,b +c,d @@" 한 영역. */
    public static final class DiffHunk {
        public int oldStart;
        public int oldCount;
        public int newStart;
        public int newCount;
        public List<DiffLine> lines = new ArrayList<>();
    }

    /** 줄 분할 결과 + 후행 개행 여부. "hello\n"과 "hello"를 구분. */
    public record SplitLinesResult(List<String> lines, boolean hasTrailingNewline) {
    }

    /** 텍스트 추출 결과. "빈 문서(success=true,text=\"\")"와 "추출 실패(success=false)"를 구분. */
    public record ExtractionResult(boolean success, String text) {
        public static ExtractionResult failed() {
            return new ExtractionResult(false, "");
        }
    }

    /** 전체 비교 결과. C++ struct DiffResult 직역. */
    public static final class DiffResult {
        public boolean isBinary = false;
        public DiffMethod method = DiffMethod.TEXT_DIRECT;
        public String oldHash = "";
        public String newHash = "";
        public int addedLines = 0;
        public int deletedLines = 0;
        public List<DiffHunk> hunks = new ArrayList<>();
        public String unifiedDiff = "";
        public String summary = "";
        public boolean oldTrailingNewline = true;
        public boolean newTrailingNewline = true;
    }
}
