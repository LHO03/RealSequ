package com.docversion.dlp;

import com.docversion.dlp.api.Severity;
import com.docversion.dlp.rule.PatternRule;
import com.docversion.dlp.rule.RuleProvider;
import com.docversion.mapper.DlpRuleMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * dlp_patterns 테이블 기반 규칙 공급자. (RD-SRS-5.4, 5.6)
 *
 * <p>dlp-core는 데이터베이스를 알지 못한다. 규칙이 어디에서 오는지는
 * {@link RuleProvider} 뒤에 숨기고, DB 접근은 이 클래스가 담당한다.
 * 이 경계 덕분에 향후 dlp-core를 별도 서비스로 떼어낼 수 있다.
 *
 * <p><b>캐시가 핵심이다.</b> 정규식 컴파일은 비용이 크므로 문서마다 수행하면
 * 안 된다. 시동 시 1회 적재하여 컴파일된 상태로 보관하고, 규칙 변경 시에만
 * 다시 적재해 교체한다. 즉 DB는 저장소일 뿐 탐지 경로에 있지 않다.
 *
 * <p>교체는 참조 치환 한 번으로 이루어진다. volatile 필드에 새 목록을 대입하면
 * 읽는 쪽은 항상 완결된 목록을 본다. 배경 작업자가 동시에 읽어도 안전하다.
 */
@Component
public class DbRuleProvider implements RuleProvider {

    private static final Logger log = LoggerFactory.getLogger(DbRuleProvider.class);

    private final DlpRuleMapper ruleMapper;
    private final int threshold;

    /** 컴파일된 규칙. 교체는 이 참조를 통째로 갈아끼우는 방식으로 원자적이다. */
    private volatile List<PatternRule> cached = List.of();

    public DbRuleProvider(DlpRuleMapper ruleMapper,
                          @Value("${docversion.dlp.threshold:50}") int threshold) {
        this.ruleMapper = ruleMapper;
        this.threshold = threshold;
    }

    @PostConstruct
    public void init() {
        reload();
    }

    /**
     * 규칙을 다시 적재한다. 규칙 변경 API에서 호출한다.
     *
     * <p>적재 도중 실패하면 기존 캐시를 유지한다. 규칙이 비어버리면
     * 모든 검사가 판정 불가가 되므로, 부분 실패보다 이전 상태 유지가 안전하다.
     *
     * @return 적재된 규칙 수
     */
    public int reload() {
        try {
            List<Map<String, Object>> rows = ruleMapper.selectActivePatterns();
            List<PatternRule> compiled = new ArrayList<>(rows.size());

            for (Map<String, Object> row : rows) {
                String name = str(row.get("patternName"));
                try {
                    compiled.add(toRule(row));
                } catch (RuntimeException e) {
                    // 규칙 하나의 오류가 나머지를 막아서는 안 된다.
                    // 잘못된 정규식이 관리 화면으로 들어올 수 있으므로 건너뛰고 계속한다.
                    log.error("규칙 '{}' 적재 실패(건너뜀): {}", name, e.getMessage());
                }
            }

            // 적재할 규칙이 DB에 있었는데 하나도 컴파일되지 않았다면, 그 결과로 캐시를
            // 비워서는 안 된다. 규칙이 0건이면 어떤 문서에서도 점수가 쌓이지 않아
            // 전부 "민감하지 않음"으로 판정된다. 탐지가 꺼진 것이 아니라 통과한 것처럼
            // 보이므로 알아채기 어렵다. 이전 규칙을 유지하는 편이 안전하다.
            if (compiled.isEmpty() && !rows.isEmpty()) {
                log.error("DLP 규칙 {}건이 모두 컴파일에 실패했습니다. 기존 규칙 {}건을 유지합니다. "
                        + "정규식을 확인하십시오.", rows.size(), cached.size());
                return cached.size();
            }

            this.cached = List.copyOf(compiled);

            if (compiled.isEmpty()) {
                // DB에 활성 규칙이 하나도 없는 상태. 이 경우 모든 검사가 NOT_SENSITIVE로
                // 판정되며, 겉보기에는 시스템이 정상 동작하는 것과 구분되지 않는다.
                //
                // 흔한 원인은 마이그레이션 누락이다. baseline-on-migrate가 켜져 있으므로
                // 기존 테이블이 있는 DB에 합류하면 V14(규칙 5건 적재)가 건너뛰어질 수 있다.
                // 탐지율을 측정할 때 이 상태로 시작하면 결과가 전부 0으로 나온다.
                log.error("DLP 활성 규칙이 0건입니다. 모든 문서가 '민감하지 않음'으로 판정됩니다. "
                        + "dlp_patterns 테이블과 Flyway 이력(V14)을 확인하십시오.");
            } else {
                log.info("DLP 규칙 {}건 적재 완료(임계값 {})", compiled.size(), threshold);
            }
            return compiled.size();

        } catch (RuntimeException e) {
            log.error("DLP 규칙 적재 실패. 기존 규칙 {}건을 유지합니다.", cached.size(), e);
            return cached.size();
        }
    }

    @Override
    public List<PatternRule> activeRules() {
        return cached;
    }

    @Override
    public int threshold() {
        return threshold;
    }

    private PatternRule toRule(Map<String, Object> row) {
        return PatternRule.compile(
                str(row.get("patternName")),
                str(row.get("displayName")),
                str(row.get("regex")),
                severityOf(str(row.get("severity"))),
                num(row.get("score")),
                num(row.get("scoreVerified")),
                str(row.get("validator")),
                str(row.get("contextRegex")),
                num(row.get("contextWindow")),
                num(row.get("maxHitsScored")),
                num(row.get("maskKeepPrefix")),
                num(row.get("maskKeepSuffix")));
    }

    /** 알 수 없는 심각도 문자열은 MEDIUM으로 둔다. 규칙을 버리지 않는다. */
    private static Severity severityOf(String s) {
        if (s == null) {
            return Severity.MEDIUM;
        }
        try {
            return Severity.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Severity.MEDIUM;
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }
}
