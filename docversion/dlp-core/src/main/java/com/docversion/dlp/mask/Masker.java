package com.docversion.dlp.mask;

/**
 * 탐지값 마스킹. (RD-SRS-5.4)
 *
 * <p>선행 산출물의 MatchResult는 매칭된 문자열을 원문 그대로 보유했다.
 * 그 값이 DB나 로그, API 응답으로 흘러가면 탐지 결과 자체가 2차 유출
 * 경로가 된다. 선행 문서(고도화 고려사항 4절)도 같은 우려를 제기했으나
 * 자료 구조가 그 권고를 구현할 수 없는 형태였다.
 *
 * <p>여기서는 규칙별로 지정된 만큼만 앞뒤를 남기고 나머지를 가린다.
 * 남길 길이는 dlp_patterns의 mask_keep_prefix / mask_keep_suffix로 정한다.
 *
 * <pre>
 *   주민등록번호 (prefix 7, suffix 0)
 *     861203-1234567  →  861203-*******
 *   신용카드 (prefix 4, suffix 4)
 *     4999-1234-5678-9012  →  4999***********9012
 *   계좌번호 (prefix 0, suffix 0)
 *     123-45-678901  →  *************
 * </pre>
 *
 * <p>구분자도 함께 가린다. 하이픈 위치가 남으면 자릿수 구성이 드러나
 * 복원 단서가 되기 때문이다.
 */
public final class Masker {

    private static final char MASK_CHAR = '*';

    /** 마스킹 결과가 지나치게 길어지지 않도록 하는 상한. DB 컬럼은 256자다. */
    private static final int MAX_LENGTH = 200;

    private Masker() {
    }

    /**
     * 앞뒤 지정 길이만 남기고 가린다.
     *
     * @param raw        원문
     * @param keepPrefix 앞에서 남길 문자 수
     * @param keepSuffix 뒤에서 남길 문자 수
     * @return 마스킹된 문자열. 원문 복원이 불가능한 형태
     */
    public static String mask(String raw, int keepPrefix, int keepSuffix) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }

        String value = raw.length() > MAX_LENGTH ? raw.substring(0, MAX_LENGTH) : raw;
        int len = value.length();

        int prefix = Math.max(0, keepPrefix);
        int suffix = Math.max(0, keepSuffix);

        // 남길 길이의 합이 원문보다 길거나 같으면 전부 노출되어 마스킹의 의미가 없다.
        // 이 경우 전부 가린다. 규칙 설정 실수로 원문이 새는 것을 막는 안전장치다.
        if (prefix + suffix >= len) {
            return repeat(len);
        }

        return value.substring(0, prefix)
                + repeat(len - prefix - suffix)
                + value.substring(len - suffix);
    }

    private static String repeat(int n) {
        return String.valueOf(MASK_CHAR).repeat(Math.max(0, n));
    }
}
