package com.docversion.dlp.api;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 검사 결과. (RD-SRS-5.1 / 5.2)
 *
 * <p>판정은 점수 합산 방식이다. 규칙마다 점수를 부여하고 탐지된 항목의 점수를
 * 더해 임계값과 비교한다. 선행 산출물은 "정규식 매칭이 하나라도 있거나 휴리스틱
 * 점수가 임계값 초과"라는 논리합 구조였는데, 5.5(머신러닝 기반 탐지)를 이번
 * 범위에서 제외하면서 뒤쪽 조건이 사라지므로 점수제로 대체한다.
 *
 * <p>점수제를 택하면 주민등록번호 1건처럼 단독으로 확정적인 항목과
 * 이메일 여러 건처럼 누적으로 의미를 갖는 항목을 같은 척도로 다룰 수 있다.
 * 또한 체크섬 검증 통과 여부에 따라 같은 규칙에 다른 점수를 줄 수 있다.
 *
 * @param verdict    판정. UNDETERMINED는 안전을 의미하지 않는다
 * @param totalScore 탐지 항목 점수의 합계
 * @param threshold  이 검사에 적용된 임계값. 결과 해석의 근거로 함께 보관한다
 * @param findings   탐지 항목 목록. 원문은 포함하지 않는다
 * @param method     판정 방법 식별자 (예: RULE, RULE+ML). 감사 추적용
 * @param note       판정 불가 사유 등 부가 설명. 없으면 null
 */
public record ScanResult(
        ScanVerdict verdict,
        int totalScore,
        int threshold,
        List<Finding> findings,
        String method,
        String note
) {
    public ScanResult {
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(method, "method");
        findings = (findings == null)
                ? Collections.emptyList()
                : List.copyOf(findings);
    }

    /**
     * 탐지 항목과 임계값으로부터 판정을 도출한다.
     * 총점이 임계값 이상이면 민감으로 본다.
     */
    public static ScanResult of(List<Finding> findings, int threshold, String method) {
        int total = findings == null ? 0 : findings.stream().mapToInt(Finding::score).sum();
        ScanVerdict verdict = (total >= threshold) ? ScanVerdict.SENSITIVE : ScanVerdict.NOT_SENSITIVE;
        return new ScanResult(verdict, total, threshold, findings, method, null);
    }

    /**
     * 판정 불가 결과를 만든다. 텍스트 추출 실패, 형식 미지원, 엔진 오류에 사용한다.
     * 호출부는 이 결과를 민감하지 않음으로 취급해서는 안 된다.
     */
    public static ScanResult undetermined(String method, String reason) {
        return new ScanResult(ScanVerdict.UNDETERMINED, 0, 0, List.of(), method, reason);
    }

    /** 탐지 항목 중 가장 높은 심각도. 항목이 없으면 null. */
    public Severity highestSeverity() {
        return findings.stream()
                .map(Finding::severity)
                .min(Enum::compareTo)   // HIGH가 서수 0이므로 min이 최고 심각도
                .orElse(null);
    }

    /** 차단 판단(RD-SRS-5.3)에 사용할 수 있는 확정 판정인지 여부. */
    public boolean isConclusive() {
        return verdict != ScanVerdict.UNDETERMINED;
    }
}
