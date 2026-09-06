package com.docversion.dlp.validate;

/**
 * 신용카드번호 Luhn 검증. (RD-SRS-5.4)
 *
 * <p>카드번호 체계의 표준 검사식이다. 오른쪽부터 한 자리 건너 두 배로 만들고
 * 두 자리가 되면 자릿수를 더한 뒤, 전체 합이 10으로 나누어떨어지면 유효하다.
 *
 * <p>선행 산출물(2세대)의 카드 정규식은 19~20자리를 요구해 실제 16자리
 * 카드번호를 전혀 탐지하지 못했다. 시험에 카드 항목이 없어 11건 통과에도
 * 발견되지 않은 결함이다. 정규식을 바로잡으면서 Luhn 검증을 함께 넣어
 * 형식만 맞는 임의 숫자열과 실제 카드번호를 구분한다.
 */
public final class LuhnValidator implements Validator {

    public static final String NAME = "LUHN";

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
        if (digits.length() < 12 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean doubling = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (doubling) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            doubling = !doubling;
        }
        return sum % 10 == 0;
    }
}
