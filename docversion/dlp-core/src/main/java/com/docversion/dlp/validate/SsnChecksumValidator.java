package com.docversion.dlp.validate;

/**
 * 주민등록번호 검증번호 확인. (RD-SRS-5.4)
 *
 * <p>13자리 중 마지막 자리는 앞 12자리로부터 계산되는 검증번호다.
 * 각 자리에 가중치 2,3,4,5,6,7,8,9,2,3,4,5를 곱해 합한 뒤
 * 11로 나눈 나머지를 11에서 빼고 다시 10으로 나눈 나머지가 검증번호가 된다.
 *
 * <p>이 검증을 통과하면 임의로 만들어낸 숫자열이 아닐 가능성이 매우 높다.
 * 다만 통과하지 못했다고 탈락시키지는 않는다. 오타가 섞인 실제 주민번호를
 * 놓치면 유출 차단이 무력화되므로, 점수만 낮춰(100 → 60) 여전히
 * 임계값을 넘도록 규칙을 설계했다.
 */
public final class SsnChecksumValidator implements Validator {

    public static final String NAME = "SSN_CHECKSUM";
    private static final int[] WEIGHTS = {2, 3, 4, 5, 6, 7, 8, 9, 2, 3, 4, 5};

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isValid(String matched) {
        if (matched == null) {
            return false;
        }
        String digits = matched.replaceAll("\\D", "");
        if (digits.length() != 13) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (digits.charAt(i) - '0') * WEIGHTS[i];
        }
        int expected = (11 - (sum % 11)) % 10;
        return expected == (digits.charAt(12) - '0');
    }
}
