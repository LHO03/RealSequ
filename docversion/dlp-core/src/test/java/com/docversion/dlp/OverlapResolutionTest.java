package com.docversion.dlp;

import com.docversion.dlp.api.Finding;
import com.docversion.dlp.api.ScanRequest;
import com.docversion.dlp.api.ScanResult;
import com.docversion.dlp.api.ScanVerdict;
import com.docversion.dlp.api.Severity;
import com.docversion.dlp.rule.PatternRule;
import com.docversion.dlp.rule.RuleProvider;
import com.docversion.dlp.rule.StaticRuleProvider;
import com.docversion.dlp.scan.RuleBasedScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 겹침 해소 우선순위 시험. (RD-SRS-5.4 / F8)
 *
 * <p>여러 규칙이 같은 문자열 구간을 잡으면 하나만 남긴다. <b>어느 쪽을 남기느냐</b>가
 * 주제다. 종전 구현은 정렬 1차 키가 점수가 아니라 <b>위치</b>여서 문서에서 먼저 나온
 * 항목이 점수와 무관하게 이겼다. 코드 주석은 "해소 기준은 점수"라고 적혀 있었으므로
 * 문서와 동작이 어긋나 있었다.
 *
 * <p><b>이 결함이 위험한 이유는 지표에 드러나지 않기 때문이다.</b> 밀려난 쪽도 대개
 * 임계값을 넘으므로 판정은 그대로다. 탐지율도 오탐율도 변하지 않고 탐지 목록만
 * 조용히 틀린다. 측정으로는 찾을 수 없는 종류의 오류다.
 *
 * <p><b>시험을 두 층으로 나눈 이유.</b> 아래 1)은 합성 규칙으로 해소 <b>기제</b> 자체를
 * 확인한다. 운영 규칙의 정규식이 바뀌어도 이 시험은 계속 유효하다. 2)는 실제 적재되는
 * 규칙으로 현실적인 겹침을 확인한다. 기제 시험만 두면 실제 규칙 조합에서 겹침이
 * 일어나는지 알 수 없고, 통합 시험만 두면 규칙이 바뀔 때 시험이 조용히 헛돈다.
 * 실제로 V18에서 계좌번호 정규식을 좁히자 종전의 겹침 사례가 더 이상 겹치지 않게 되었다.
 */
class OverlapResolutionTest {

    // ============================================================
    // 1) 해소 기제 — 합성 규칙으로 확인 (운영 정규식과 무관)
    // ============================================================

    /**
     * 같은 자리를 잡는 두 규칙. 점수만 다르고 위치는 뒤쪽이 더 높은 점수다.
     * 위치 기준으로 해소하면 낮은 점수가 이기고, 점수 기준이면 높은 쪽이 이긴다.
     */
    private static RuleProvider twoOverlappingRules() {
        List<PatternRule> rules = new ArrayList<>();
        // 앞에서 시작하지만 점수가 낮다
        rules.add(PatternRule.compile("LOW_EARLY", "앞선 저점수",
                "AAAA-BBBB", Severity.LOW, 10, 10, null, null, 40, 0, 0, 0));
        // 뒤에서 시작하지만 점수가 높다. 구간이 겹친다.
        rules.add(PatternRule.compile("HIGH_LATE", "뒤선 고점수",
                "BBBB-CCCC", Severity.HIGH, 100, 100, null, null, 40, 0, 0, 0));
        return new StaticRuleProvider(rules, 50);
    }

    @Test
    @DisplayName("F8 겹치면 위치가 아니라 점수가 높은 쪽이 남는다")
    void higherScoreWins_notEarlierPosition() {
        RuleBasedScanner scanner = new RuleBasedScanner(twoOverlappingRules());
        ScanResult r = scanner.scan(ScanRequest.full("f", "v", "xx AAAA-BBBB-CCCC yy", "text/plain"));

        assertEquals(1, r.findings().size(), "겹치므로 하나만 남아야 한다: " + summary(r));
        assertEquals("HIGH_LATE", r.findings().get(0).patternName(),
                "위치 기준으로 해소하면 LOW_EARLY가 이긴다. 그것이 종전 동작이었다");
        assertEquals(100, r.totalScore());
        assertEquals(ScanVerdict.SENSITIVE, r.verdict(),
                "저점수가 이기면 10점이 되어 판정까지 뒤집힌다");
    }

    // ============================================================
    // 2) 실제 적재되는 규칙에서의 겹침
    // ============================================================

    private final RuleBasedScanner scanner = new RuleBasedScanner(ProductionRules.provider());

    private ScanResult scan(String text) {
        return scanner.scan(ScanRequest.full("f1", "v1", text, "text/plain"));
    }

    private static boolean has(ScanResult r, String patternName) {
        return r.findings().stream().anyMatch(f -> f.patternName().equals(patternName));
    }

    private static String summary(ScanResult r) {
        StringBuilder sb = new StringBuilder();
        for (Finding f : r.findings()) {
            sb.append(f.patternName()).append('(').append(f.score()).append(") ");
        }
        return sb.toString().trim();
    }

    @Test
    @DisplayName("F8 카드번호가 계좌번호 규칙과 겹칠 때 카드번호가 남는다")
    void verifiedCardWinsOverOverlappingBankAccount() {
        // 카드번호 4-4-4-4는 계좌번호 정규식(2~6자리 묶음 넷)에도 부합한다.
        // 근처에 은행명이 있으면 문맥 조건까지 통과해 같은 구간을 두 규칙이 잡는다.
        ScanResult r = scan("국민은행 법인카드 4539-5787-6362-1486 결제 내역");

        assertTrue(has(r, "CREDIT_CARD"),
                "Luhn을 통과한 카드번호가 계좌번호에 밀려 사라졌다: " + summary(r));
        assertFalse(has(r, "BANK_ACCOUNT"),
                "겹치는 계좌번호는 점수가 낮으므로 버려져야 한다: " + summary(r));
        assertEquals(100, r.totalScore(), "카드 검증 통과 점수만 남아야 한다");
    }

    @Test
    @DisplayName("구간이 겹치지 않으면 낮은 점수 항목도 그대로 남는다")
    void nonOverlappingFindingsAreAllKept() {
        ScanResult r = scan("""
                퇴직정산 통지서
                주민등록번호: 850707-2000001
                연락처: 010-1111-2222
                이메일: jiwoo.choi@example.com
                """);

        assertTrue(has(r, "SSN"), summary(r));
        assertTrue(has(r, "PHONE"), "겹치지 않는 전화번호까지 지워서는 안 된다: " + summary(r));
        assertTrue(has(r, "EMAIL"), "겹치지 않는 이메일까지 지워서는 안 된다: " + summary(r));
        assertEquals(130, r.totalScore(), "100 + 20 + 10");
    }

    @Test
    @DisplayName("탐지 목록은 본문 순서로 돌아온다")
    void findingsAreReturnedInDocumentOrder() {
        ScanResult r = scan("""
                연락처: 010-1111-2222
                주민등록번호: 850707-2000001
                이메일: a@b.co.kr
                """);

        List<Finding> fs = r.findings();
        for (int i = 1; i < fs.size(); i++) {
            assertTrue(fs.get(i - 1).offset() <= fs.get(i).offset(),
                    "우선순위로 정렬한 뒤에는 다시 본문 순서로 되돌려야 한다: " + summary(r));
        }
    }

    @Test
    @DisplayName("같은 입력에는 항상 같은 결과가 나온다")
    void resolutionIsDeterministic() {
        String text = "국민은행 법인카드 4539-5787-6362-1486 결제 내역";
        String first = summary(scan(text));
        for (int i = 0; i < 30; i++) {
            assertEquals(first, summary(scan(text)),
                    "정렬 기준에 동점 처리가 빠지면 실행마다 결과가 달라진다");
        }
    }
}
