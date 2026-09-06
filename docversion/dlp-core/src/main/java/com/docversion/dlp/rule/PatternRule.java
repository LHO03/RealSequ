package com.docversion.dlp.rule;

import com.docversion.dlp.api.Severity;

import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 탐지 규칙 한 건. dlp_patterns 테이블의 한 행에 대응한다. (RD-SRS-5.4)
 *
 * <p>정규식은 생성 시점에 한 번만 컴파일하여 보관한다. 문서마다 컴파일하면
 * 비용이 크기 때문이며, 이것이 규칙을 DB에 두면서도 탐지 경로에서는
 * DB를 건드리지 않는 구조의 핵심이다.
 *
 * <p>이 타입은 dlp-core에 있으나 DB를 알지 못한다. 행을 이 형태로 옮기는
 * 일은 docversion-app이 담당한다(→ {@link RuleProvider}).
 *
 * @param name           규칙 이름. Finding.patternName으로 그대로 전달된다
 * @param displayName    화면 표시명
 * @param pattern        컴파일된 정규식
 * @param severity       심각도
 * @param score          기본 점수. 검증기가 없거나 검증에 실패했을 때 적용
 * @param scoreVerified  검증기 통과 시 점수. validator가 null이면 사용되지 않는다
 * @param validatorName  추가 검증기 이름(SSN_CHECKSUM, LUHN). 없으면 null
 * @param contextPattern 문맥 조건 정규식. 없으면 null
 * @param contextWindow  문맥 탐색 범위(매칭 앞뒤 문자 수)
 * @param maxHitsScored  점수 반영 최대 건수. 0이면 무제한
 * @param maskKeepPrefix 마스킹 시 앞에서 남길 문자 수
 * @param maskKeepSuffix 마스킹 시 뒤에서 남길 문자 수
 */
public record PatternRule(
        String name,
        String displayName,
        Pattern pattern,
        Severity severity,
        int score,
        int scoreVerified,
        String validatorName,
        Pattern contextPattern,
        int contextWindow,
        int maxHitsScored,
        int maskKeepPrefix,
        int maskKeepSuffix
) {
    public PatternRule {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(severity, "severity");
        if (displayName == null || displayName.isBlank()) {
            displayName = name;
        }
        if (contextWindow < 0) {
            throw new IllegalArgumentException("contextWindow는 음수일 수 없습니다: " + contextWindow);
        }
        if (maxHitsScored < 0) {
            throw new IllegalArgumentException("maxHitsScored는 음수일 수 없습니다: " + maxHitsScored);
        }
        if (maskKeepPrefix < 0 || maskKeepSuffix < 0) {
            throw new IllegalArgumentException("마스킹 보존 길이는 음수일 수 없습니다");
        }
    }

    /** 문맥 조건이 걸린 규칙인지. 계좌번호처럼 정규식만으로 과탐이 심한 경우에 쓴다. */
    public boolean hasContextCondition() {
        return contextPattern != null;
    }

    /** 추가 검증기가 지정된 규칙인지. 주민등록번호 체크섬, 카드 Luhn 등. */
    public boolean hasValidator() {
        return validatorName != null && !validatorName.isBlank();
    }

    /**
     * DB 문자열로부터 규칙을 만든다. 정규식 컴파일 실패는 여기서 드러난다.
     *
     * <p>선행 산출물(3세대)은 YAML에 정규식을 두었는데 역슬래시가
     * 이스케이프되지 않아 파일이 파싱조차 되지 않았다. 규칙을 DB로 옮기면서
     * 그 계층은 사라졌으나, 관리자가 잘못된 정규식을 입력할 여지는 남는다.
     * 적재 시점에 컴파일하여 문제를 조기에 드러낸다.
     *
     * @throws IllegalArgumentException 정규식이 유효하지 않은 경우
     */
    public static PatternRule compile(
            String name, String displayName, String regex, Severity severity,
            int score, int scoreVerified, String validatorName,
            String contextRegex, int contextWindow, int maxHitsScored,
            int maskKeepPrefix, int maskKeepSuffix) {

        Pattern compiled;
        try {
            compiled = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    "규칙 '" + name + "'의 정규식을 컴파일할 수 없습니다: " + e.getDescription(), e);
        }

        Pattern ctx = null;
        if (contextRegex != null && !contextRegex.isBlank()) {
            try {
                ctx = Pattern.compile(contextRegex);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException(
                        "규칙 '" + name + "'의 문맥 조건 정규식을 컴파일할 수 없습니다: " + e.getDescription(), e);
            }
        }

        return new PatternRule(name, displayName, compiled, severity,
                score, scoreVerified, validatorName,
                ctx, contextWindow, maxHitsScored, maskKeepPrefix, maskKeepSuffix);
    }
}
