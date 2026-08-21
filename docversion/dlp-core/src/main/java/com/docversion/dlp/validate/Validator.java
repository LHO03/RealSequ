package com.docversion.dlp.validate;

/**
 * 추가 검증기. 정규식이 잡아낸 후보가 실제로 유효한 값인지 확인한다. (RD-SRS-5.4)
 *
 * <p>정규식은 형식만 본다. 주민등록번호와 신용카드번호에는 검증번호가 있어
 * 실제 유효성을 산술적으로 확인할 수 있으므로, 이를 통과한 값과 형식만 맞는 값을
 * 구분해 다른 점수를 준다.
 *
 * <p>검증 실패를 곧바로 탈락으로 처리하지 않는 것이 중요하다. 문서에 주민번호를
 * 옮겨 적다 한 자리를 틀린 경우, 그것도 여전히 유출 위험이기 때문이다.
 * 검증은 점수를 낮출 뿐이며 탐지 자체는 유지한다.
 */
public interface Validator {

    /** 검증기 이름. dlp_patterns.validator 값과 일치해야 한다. */
    String name();

    /**
     * 매칭된 문자열이 유효한지 확인한다.
     *
     * @param matched 정규식이 잡아낸 원문(구분자 포함 가능)
     * @return 검증 통과 여부. 예외를 던지지 않고 false를 반환한다
     */
    boolean isValid(String matched);
}
