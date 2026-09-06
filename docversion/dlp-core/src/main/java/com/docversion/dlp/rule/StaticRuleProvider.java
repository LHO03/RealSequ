package com.docversion.dlp.rule;

import java.util.List;
import java.util.Objects;

/**
 * 고정 규칙 공급자. 시험과 단독 실행에 쓴다.
 *
 * <p>운영에서는 docversion-app이 DB 기반 구현체를 제공한다.
 * 이 클래스는 dlp-core를 Spring 문맥 없이 검증할 수 있게 해주며,
 * 선행 산출물이 규칙 적재 계층 부재로 컴파일조차 되지 않았던 문제에 대한
 * 재발 방지책이기도 하다. 엔진은 공급자 없이도 항상 시험 가능하다.
 */
public final class StaticRuleProvider implements RuleProvider {

    private final List<PatternRule> rules;
    private final int threshold;

    public StaticRuleProvider(List<PatternRule> rules, int threshold) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        this.threshold = threshold;
    }

    @Override
    public List<PatternRule> activeRules() {
        return rules;
    }

    @Override
    public int threshold() {
        return threshold;
    }
}
