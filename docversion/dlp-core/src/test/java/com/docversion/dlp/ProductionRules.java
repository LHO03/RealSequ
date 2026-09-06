package com.docversion.dlp;

import com.docversion.dlp.api.Severity;
import com.docversion.dlp.rule.PatternRule;
import com.docversion.dlp.rule.RuleProvider;
import com.docversion.dlp.rule.StaticRuleProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * 운영에 실제로 적재되는 규칙 5종의 시험용 사본. (V14 적재 + V16·V17·V18 수정)
 *
 * <p><b>왜 사본을 두는가.</b> 규칙은 데이터베이스에 있고 dlp-core는 데이터베이스를
 * 알지 못한다. 그 경계 덕분에 탐지 엔진을 스프링도 DB도 없이 단독으로 시험할 수 있고,
 * 이 파일이 그 대가다.
 *
 * <p><b>마이그레이션과 반드시 함께 고칠 것.</b> 정규식이나 배점을 바꿀 때는
 * {@code docversion-app/src/main/resources/db/migration/} 의 마이그레이션과
 * 이 파일을 같이 고쳐야 한다. 한쪽만 바뀌면 시험은 통과하는데 운영은 다르게
 * 동작하는, 가장 알아채기 어려운 형태의 어긋남이 생긴다.
 *
 * <p>{@code DefaultRules}가 아니라 이 사본을 쓰는 이유는, 시험이 확인하려는 것이
 * "엔진이 잘 도는가"가 아니라 <b>"실제로 적재되는 규칙 조합이 옳게 동작하는가"</b>이기
 * 때문이다.
 */
final class ProductionRules {

    /** 임계값. dlp.threshold 기본값과 같아야 한다. */
    static final int THRESHOLD = 50;

    /** 계좌번호 문맥 조건 — 은행명 또는 거래 어휘 (V14). */
    private static final String BANK_CONTEXT =
            "(국민|신한|우리|하나|농협|기업|씨티|SC제일|카카오뱅크|케이뱅크|토스뱅크|"
                    + "수협|새마을금고|신협|우체국|산업|대구|부산|경남|광주|전북|제주|"
                    + "계좌|예금주|입금|송금|이체|account)";

    /** 주민등록번호 (V14). 생년월일·성별코드 형식 검증 + 체크섬. */
    static final String SSN_REGEX =
            "\\b(\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01]))[- ]?([1-8]\\d{6})\\b";

    /** 신용카드번호 (V18). Amex 15자리(4-6-5) + 그 외 16자리(4-4-4-4). */
    static final String CARD_REGEX =
            "\\b(?:3[47]\\d{2}[- ]?\\d{6}[- ]?\\d{5}"
                    + "|(?:4\\d{3}|5[1-5]\\d{2}|6011)[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4})\\b";

    /**
     * 계좌번호 (V18). 하이픈만 인정하고 전체 13자 이상(숫자 11자리 이상)을 요구한다.
     * 휴대전화와 국가번호가 붙은 번호는 제외하고, 숫자 묶음이 더 이어지면 매칭하지 않는다.
     */
    static final String BANK_REGEX =
            "(?<!\\+)\\b(?!01[0-9][-.]?\\d{3,4}[-.]?\\d{4}\\b)(?=[\\d-]{13})"
                    + "\\d{2,6}-\\d{2,6}-\\d{2,6}(?:-\\d{2,6})?\\b(?!-\\d)";

    /** 휴대전화번호 (V14). 01X 대역만. 유선번호는 과탐이 커서 제외. */
    static final String PHONE_REGEX = "\\b01[0-9][-. ]?\\d{3,4}[-. ]?\\d{4}\\b";

    /** 이메일 주소 (V14). 최상위 도메인을 요구해 내부 주소를 배제. */
    static final String EMAIL_REGEX = "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b";

    private ProductionRules() {
    }

    static RuleProvider provider() {
        List<PatternRule> rules = new ArrayList<>();
        rules.add(PatternRule.compile("SSN", "주민등록번호", SSN_REGEX,
                Severity.HIGH, 60, 100, "SSN_CHECKSUM", null, 40, 0, 3, 7));
        rules.add(PatternRule.compile("CREDIT_CARD", "신용카드번호", CARD_REGEX,
                Severity.HIGH, 60, 100, "LUHN", null, 40, 0, 4, 4));
        rules.add(PatternRule.compile("BANK_ACCOUNT", "계좌번호", BANK_REGEX,
                Severity.MEDIUM, 80, 80, null, BANK_CONTEXT, 40, 0, 0, 0));
        rules.add(PatternRule.compile("PHONE", "휴대전화번호", PHONE_REGEX,
                Severity.LOW, 20, 20, null, null, 40, 0, 3, 4));
        rules.add(PatternRule.compile("EMAIL", "이메일주소", EMAIL_REGEX,
                Severity.LOW, 10, 10, null, null, 40, 0, 2, 0));
        return new StaticRuleProvider(rules, THRESHOLD);
    }
}
