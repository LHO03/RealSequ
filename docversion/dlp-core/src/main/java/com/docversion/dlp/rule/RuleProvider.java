package com.docversion.dlp.rule;

import java.util.List;

/**
 * 규칙 공급자. (RD-SRS-5.4, 5.6)
 *
 * <p>dlp-core는 데이터베이스를 알지 못한다. 규칙이 어디에서 오는지는
 * 이 인터페이스 뒤에 숨기고, 구현은 docversion-app이 담당한다.
 * 이 경계가 있어야 향후 dlp-core를 별도 서비스로 떼어낼 수 있다.
 *
 * <p>구현체는 규칙을 매 호출마다 조회하지 않는다. 시동 시 1회 적재하여
 * 컴파일된 {@link PatternRule}을 메모리에 보관하고, 규칙 변경 API가
 * 호출될 때만 다시 적재해 교체한다. 정규식 컴파일은 비용이 크므로
 * 문서마다 수행하면 안 된다.
 *
 * <p>다중 스레드에서 호출되므로 구현체는 스레드 안전해야 한다.
 * 교체는 참조 치환(volatile 필드 대입 등)으로 원자적으로 수행한다.
 */
public interface RuleProvider {

    /**
     * 현재 활성 규칙 목록. 비활성(is_active=0) 규칙은 포함하지 않는다.
     * 규칙이 하나도 없으면 빈 목록을 반환한다(null 금지).
     */
    List<PatternRule> activeRules();

    /**
     * 민감 판정 임계값. 탐지 항목 점수의 합이 이 값 이상이면 민감으로 본다.
     */
    int threshold();
}
