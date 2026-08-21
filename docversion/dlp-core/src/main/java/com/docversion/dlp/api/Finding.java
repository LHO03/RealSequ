package com.docversion.dlp.api;

import java.util.Objects;

/**
 * 탐지 항목 한 건. (RD-SRS-5.4)
 *
 * <p>이 자료 구조에는 탐지된 원문이 담기지 않는다. 선행 산출물의
 * MatchResult는 매칭 문자열을 그대로 보유했는데, 그 값이 로그나 API 응답으로
 * 흘러가면 탐지 결과 자체가 2차 유출 경로가 된다.
 * 여기서는 {@code maskedValue}만 보유하고 원문은 엔진 밖으로 내보내지 않는다.
 *
 * <p>{@code offset}과 {@code length}는 1세대 산출물에 있다가 2세대에서 사라진 정보다.
 * "원문 대신 위치만 기록한다"는 로깅 정책을 구현하려면 이 두 값이 반드시 필요하므로
 * 다시 계약에 포함한다.
 *
 * @param patternName 규칙 이름 (예: SSN, CREDIT_CARD). 규칙 테이블의 식별자와 일치
 * @param severity    규칙에 부여된 심각도
 * @param score       이 항목이 총점에 기여한 점수. 검증기 통과 여부에 따라 달라질 수 있다
 * @param offset      검사 대상 텍스트에서의 시작 위치 (0-기준)
 * @param length      매칭된 길이
 * @param maskedValue 일부만 노출한 값. 원문 복원이 불가능한 형태여야 한다
 * @param verified    추가 검증기(체크섬 등)를 통과했는지 여부. 검증기가 없는 규칙은 false
 */
public record Finding(
        String patternName,
        Severity severity,
        int score,
        int offset,
        int length,
        String maskedValue,
        boolean verified
) {
    public Finding {
        Objects.requireNonNull(patternName, "patternName");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(maskedValue, "maskedValue");
        if (offset < 0) {
            throw new IllegalArgumentException("offset은 음수일 수 없습니다: " + offset);
        }
        if (length <= 0) {
            throw new IllegalArgumentException("length는 양수여야 합니다: " + length);
        }
        if (score < 0) {
            throw new IllegalArgumentException("score는 음수일 수 없습니다: " + score);
        }
    }
}
