package com.docversion.dlp;

/**
 * 버전 비교 결과에서 추가된 줄만 추린다. (RD-SRS-5.2)
 *
 * <p>5.2는 "수정된 자료의 민감 데이터 포함 여부"를 묻는다. 이를 위한 입력을
 * 새로 만들 필요가 없다. 9.4 버전 비교가 이미 unified diff 형식으로 변경 내역을
 * 산출해 두었으므로, 거기서 추가된 줄만 뽑으면 그대로 검사 대상이 된다.
 * 즉 버전 비교 기능이 변경분 검사의 전처리기 역할을 겸한다.
 *
 * <p>unified diff 형식은 다음과 같다.
 * <pre>
 *   @@ -1,3 +1,4 @@
 *    변경되지 않은 줄
 *   -삭제된 줄
 *   +추가된 줄
 * </pre>
 *
 * <p><b>추가된 줄만 본다.</b> 삭제된 줄은 다루지 않는다. 그 결과 한 줄 안에서
 * 일부만 수정된 경우 그 줄 전체가 새로 유입된 것으로 집계된다.
 * 예를 들어 전화번호 뒷자리만 바꾸면 diff는 삭제 한 줄과 추가 한 줄로 표현하므로,
 * 추가된 줄에 담긴 전화번호가 "새로 들어온 것"으로 잡힌다.
 * 실제로는 이미 있던 항목이 바뀐 것이지만, 과탐 방향이므로 유출 차단 관점에서는
 * 안전한 쪽이다. 삭제된 줄과 대조해 차집합을 구하는 방식은 이번 범위에서 제외한다.
 *
 * <p>변경분 검사는 보조 수단이다. 판정의 정본은 전체 검사(FULL)이며,
 * 이 범위만으로는 이전 버전에 이미 있던 민감 데이터를 놓친다.
 */
public final class UnifiedDiffLines {

    private UnifiedDiffLines() {
    }

    /**
     * unified diff에서 추가된 줄('+' 접두)만 모아 개행으로 잇는다.
     *
     * @param unifiedDiff 버전 비교 결과. null이거나 비어 있으면 빈 문자열
     * @return 추가된 줄들. 접두 기호는 제거된 상태
     */
    public static String addedLines(String unifiedDiff) {
        if (unifiedDiff == null || unifiedDiff.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (String line : unifiedDiff.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            char c = line.charAt(0);

            // '@@'로 시작하는 구간 머리글, '\'로 시작하는 개행 표시,
            // ' '(문맥), '-'(삭제)는 모두 건너뛴다.
            if (c != '+') {
                continue;
            }
            // '+++' 형태의 파일 머리글은 이 구현에서 생성되지 않으나 방어한다.
            if (line.startsWith("+++")) {
                continue;
            }

            sb.append(line, 1, line.length()).append('\n');
        }
        return sb.toString();
    }

    /** 추가된 줄이 하나라도 있는지. 없으면 변경분 검사를 적재할 이유가 없다. */
    public static boolean hasAddedLines(String unifiedDiff) {
        return !addedLines(unifiedDiff).isBlank();
    }
}
