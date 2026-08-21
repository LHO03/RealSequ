package com.docversion.dlp.rule;

import com.docversion.dlp.api.Severity;

import java.util.List;

/**
 * V14 초기 규칙과 동일한 규칙 집합. 시험과 단독 실행에 쓴다. (RD-SRS-5.4)
 *
 * <p>운영에서는 dlp_patterns 테이블이 규칙의 유일한 기준이며, 이 클래스는
 * 사용되지 않는다. 여기 두는 이유는 두 가지다.
 *
 * <ul>
 *   <li>dlp-core를 DB 없이 단위 시험할 수 있게 한다.</li>
 *   <li>마이그레이션의 정규식과 배점이 의도대로인지 자바 쪽에서 대조할 수 있다.
 *       DB 리터럴의 역슬래시 이스케이프가 잘못되면 저장값이 달라지는데,
 *       그런 사고를 시험으로 잡아내기 위한 기준값 역할을 한다.</li>
 * </ul>
 *
 * <p>V14의 값을 바꾸면 이 파일도 함께 고쳐야 한다. 두 곳이 어긋나면
 * 시험은 통과하는데 운영에서는 다르게 동작하는 상황이 생긴다.
 */
public final class DefaultRules {

    /** V14 및 운영 설정과 동일한 민감 판정 임계값. */
    public static final int THRESHOLD = 50;

    private DefaultRules() {
    }

    /**
     * 초기 규칙 5종. V14__dlp_rules.sql의 INSERT와 같은 내용이다.
     *
     * <p>점수 상한(maxHitsScored)은 V16에서 모두 0(무제한)으로 바뀌었으므로
     * 여기서도 0으로 둔다.
     */
    public static List<PatternRule> all() {
        return List.of(ssn(), creditCard(), bankAccount(), phone(), email());
    }

    /**
     * 주민등록번호. 생년월일 6자리 + 성별코드 + 6자리.
     *
     * <p>월(01-12)과 일(01-31), 성별코드(1-8)를 형식 단계에서 검증한다.
     * 성별코드 1,2는 1900년대 내국인, 3,4는 2000년대 내국인,
     * 5,6과 7,8은 각각 그 시기의 외국인이다. 외국인등록번호도 민감정보이므로
     * 함께 포함한다. 9,0은 1800년대 출생자용이라 제외한다.
     *
     * <p>체크섬 통과 시 100점, 형식만 맞으면 60점. 둘 다 임계값(50)을 넘으므로
     * 오타가 섞인 실제 주민번호도 놓치지 않으면서 심각도는 구분된다.
     */
    public static PatternRule ssn() {
        return PatternRule.compile(
                "SSN", "주민등록번호",
                "\\b(\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01]))[- ]?([1-8]\\d{6})\\b",
                Severity.HIGH, 60, 100, "SSN_CHECKSUM",
                null, 40, 0, 7, 0);
    }

    /**
     * 신용카드번호. Visa(4) / MasterCard(51-55) / Discover(6011) / Amex(34,37).
     *
     * <p>선행 산출물의 규칙은 19~20자리를 요구해 실제 16자리 카드번호를
     * 전혀 탐지하지 못했다. 4자리 그룹 4회로 바로잡고 Luhn 검증을 붙였다.
     */
    public static PatternRule creditCard() {
        return PatternRule.compile(
                "CREDIT_CARD", "신용카드번호",
                "\\b(?:4\\d{3}|5[1-5]\\d{2}|6011|3[47]\\d{2})[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}\\b",
                Severity.HIGH, 60, 100, "LUHN",
                null, 40, 0, 4, 4);
    }

    /**
     * 계좌번호. 문맥 조건이 필수인 유일한 규칙이다.
     *
     * <p>은행별 자릿수 체계가 제각각이라 형식만으로는 주문번호·사번·도서번호와
     * 구분되지 않는다. 앞뒤 40자 안에 은행명이나 거래 어휘가 있을 때만 인정한다.
     *
     * <p>앞의 부정형 전방탐색은 휴대전화번호를 제외한다(V17). 010-1234-5678도
     * 3-4-4 자리라 이 정규식에 부합하는데, 문서에 연락처와 계좌번호가 함께 있으면
     * 문맥 조건까지 통과해 전화번호가 계좌번호로 잘못 보고되었다.
     */
    public static PatternRule bankAccount() {
        return PatternRule.compile(
                "BANK_ACCOUNT", "계좌번호",
                "\\b(?!01[0-9][-. ]?\\d{3,4}[-. ]?\\d{4}\\b)\\d{2,6}[- ]\\d{2,6}[- ]\\d{2,6}\\b",
                Severity.MEDIUM, 80, 80, null,
                "(국민|신한|우리|하나|농협|기업|씨티|SC제일|카카오뱅크|케이뱅크|토스뱅크|수협|새마을금고|신협|우체국|산업|대구|부산|경남|광주|전북|제주|계좌|예금주|입금|송금|이체|account)",
                40, 0, 0, 0);
    }

    /**
     * 휴대전화번호. 01X 대역 전체를 포함한다.
     *
     * <p>011/016/017/018/019는 현재 신규 발급되지 않으나 과거 문서에는
     * 남아 있으므로 탐지 대상이다. 유선번호는 자릿수가 들쭉날쭉해 과탐이
     * 급증하므로 제외한다.
     */
    public static PatternRule phone() {
        return PatternRule.compile(
                "PHONE", "휴대전화번호",
                "\\b01[0-9][-. ]?\\d{3,4}[-. ]?\\d{4}\\b",
                Severity.LOW, 20, 20, null,
                null, 40, 0, 3, 4);
    }

    /**
     * 이메일 주소. 최상위 도메인을 요구해 내부 주소 오탐을 배제한다.
     */
    public static PatternRule email() {
        return PatternRule.compile(
                "EMAIL", "이메일주소",
                "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b",
                Severity.LOW, 10, 10, null,
                null, 40, 0, 2, 0);
    }

    /** 초기 규칙 전체를 담은 공급자. */
    public static RuleProvider provider() {
        return new StaticRuleProvider(all(), THRESHOLD);
    }
}
