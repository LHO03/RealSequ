package com.docversion.dlp;

import com.docversion.dlp.api.Finding;
import com.docversion.dlp.api.ScanRequest;
import com.docversion.dlp.api.ScanResult;
import com.docversion.dlp.api.ScanVerdict;
import com.docversion.dlp.scan.RuleBasedScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-08-31 탐지율 측정에서 실제로 나온 오탐·미탐을 회귀 시험으로 고정한다. (V18 / F5 · F11)
 *
 * <p><b>여기 적힌 문장은 지어낸 것이 아니라 측정에 쓴 시험 문서의 실제 본문이다.</b>
 * 측정 결과는 이랬다.
 *
 * <ul>
 *   <li>오탐 14건 — <b>전량</b> 계좌번호 규칙 하나에서 나왔다. 날짜·사번·국가번호가 붙은
 *       전화번호·유선 대표번호·사업자등록번호·공백 건너뛰기, 여섯 가지 형태.</li>
 *   <li>미탐 3건 — <b>전량</b> Amex 15자리. 정규식이 4-4-4-4를 요구해 15자리가 떨어져 나갔다.</li>
 *   <li>다른 규칙의 오탐·미탐은 0이었다.</li>
 * </ul>
 *
 * <p>즉 이 두 규칙이 전체 오차의 100%를 차지했다. V18이 그 둘을 고쳤고,
 * 이 시험이 되돌아가지 않도록 지킨다.
 *
 * <p><b>규칙을 측정 전에 손대지 않은 이유</b>도 함께 기록해 둔다. 알려진 결함을 미리
 * 고치고 측정하면 "무엇을 고쳐야 하는가"에 대한 답이 결과에 남지 않는다. 함정 문서를
 * 일부러 넣어 숫자로 드러나게 한 뒤에 고쳤다.
 */
class MeasuredDefectsTest {

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
        return sb.length() == 0 ? "(탐지 없음)" : sb.toString().trim();
    }

    private void assertNotSensitive(String label, String text) {
        ScanResult r = scan(text);
        assertFalse(has(r, "BANK_ACCOUNT"),
                label + " — 계좌번호로 잘못 탐지되었다: " + summary(r));
        assertEquals(ScanVerdict.NOT_SENSITIVE, r.verdict(),
                label + " — 판정: " + r.verdict() + " " + summary(r));
    }

    // ============================================================
    // 오탐 6종 — 측정에서 FP로 나온 문서들 (dlp-eval C01~C06)
    // ============================================================

    @Test
    @DisplayName("C01 날짜가 계좌번호로 잡히지 않는다")
    void date_isNotBankAccount() {
        // '입금'이 문맥 조건을 통과시키고, 2024-01-15가 4-2-2 자리로 부합했다.
        assertNotSensitive("C01 날짜", "청구 안내문\n\n입금일: 2024-01-15 까지 처리 바랍니다.");
    }

    @Test
    @DisplayName("C02 사번이 계좌번호로 잡히지 않는다")
    void employeeNumber_isNotBankAccount() {
        assertNotSensitive("C02 사번", "예금주 확인용 사번 12-3456-78 로 조회 부탁드립니다.");
    }

    @Test
    @DisplayName("C03 국가번호가 붙은 전화번호가 계좌번호로 잡히지 않는다")
    void internationalPhone_isNotBankAccount() {
        // V17의 휴대전화 전방탐색은 '+82-10-...' 형태를 막지 못했다.
        assertNotSensitive("C03 국가번호 전화", "연락처 +82-10-1234-5678 (급여계좌 문의)");
    }

    @Test
    @DisplayName("C04 유선 대표번호가 계좌번호로 잡히지 않는다")
    void landline_isNotBankAccount() {
        assertNotSensitive("C04 유선 대표번호", "대표번호 02-1234-5678 로 문의 (계좌 안내)");
        assertNotSensitive("C04 지역 유선번호", "송금 문의는 031-123-4567 번으로");
    }

    @Test
    @DisplayName("C05 사업자등록번호가 계좌번호로 잡히지 않는다")
    void businessRegistrationNumber_isNotBankAccount() {
        assertNotSensitive("C05 사업자등록번호", "사업자등록번호 123-45-67890 (세금계산서 입금)");
    }

    @Test
    @DisplayName("C06 공백을 건너뛰어 무관한 두 숫자를 묶지 않는다")
    void separatorDoesNotBridgeWhitespace() {
        // 종전 구분자 [- ]가 공백을 받아 '1002-345 12'를 하나로 묶었다.
        assertNotSensitive("C06 공백 건너뛰기",
                "입금 예정 금액은 1002-345 12-3456 두 건으로 나누어 처리합니다.");
    }

    // ============================================================
    // 미탐 — 측정에서 FN으로 나온 문서 (dlp-eval D01)
    // ============================================================

    @Test
    @DisplayName("D01 Amex 15자리를 탐지한다")
    void amexIsDetected() {
        ScanResult r = scan("해외출장 정산서\n\n결제카드(Amex): 3782-822463-10005\n");

        assertTrue(has(r, "CREDIT_CARD"),
                "Amex는 15자리(4-6-5)다. 4-4-4-4만 받으면 통째로 놓친다: " + summary(r));
        assertEquals(ScanVerdict.SENSITIVE, r.verdict());
        assertEquals(100, r.totalScore(), "Luhn을 통과하므로 검증 점수가 적용된다");
    }

    @Test
    @DisplayName("구분자 없는 Amex도 탐지한다")
    void amexWithoutSeparatorsIsDetected() {
        assertTrue(has(scan("카드 378282246310005 결제"), "CREDIT_CARD"));
    }

    // ============================================================
    // 고치면서 깨뜨리지 않았는지 — 진양성이 그대로 잡히는가
    // ============================================================

    @Test
    @DisplayName("실제 계좌번호는 그대로 탐지한다")
    void realBankAccountsAreStillDetected() {
        String[] samples = {
                "은행: 국민은행\n계좌번호: 123-45-678901",       // 3-2-6
                "정산금 입금 계좌\n  신한은행 110-234-567890",    // 3-3-6
                "우리은행 1002-345-678901 로 송금",             // 4-3-6
                "국민은행 123456-78-901234 계좌",               // 6-2-6
                "하나은행 123-456789-01234 입금",               // 3-6-5
        };
        for (String s : samples) {
            ScanResult r = scan(s);
            assertTrue(has(r, "BANK_ACCOUNT"), "놓쳤다: " + s + " -> " + summary(r));
            assertEquals(ScanVerdict.SENSITIVE, r.verdict(), s);
        }
    }

    @Test
    @DisplayName("농협식 4묶음 계좌를 온전히 탐지한다")
    void fourGroupAccountIsFullyMatched() {
        // 종전에는 앞 3묶음만 잘라 '352-0123-4567'로 보고했다. V18에서 나아진 부분.
        ScanResult r = scan("농협 352-0123-4567-89 로 입금 바랍니다");

        assertTrue(has(r, "BANK_ACCOUNT"), summary(r));
        Finding f = r.findings().stream()
                .filter(x -> x.patternName().equals("BANK_ACCOUNT")).findFirst().orElseThrow();
        assertEquals("352-0123-4567-89".length(), f.length(),
                "네 번째 묶음까지 한 건으로 잡아야 한다");
    }

    @Test
    @DisplayName("다른 카드는 종전과 동일하게 탐지한다")
    void otherCardsUnchanged() {
        assertTrue(has(scan("카드번호: 4539-5787-6362-1486"), "CREDIT_CARD"), "Visa");
        assertTrue(has(scan("5555-5555-5555-4444"), "CREDIT_CARD"), "MasterCard");
        assertFalse(has(scan("주문번호 1234-5678-9012-3456 확인"), "CREDIT_CARD"),
                "카드 대역이 아닌 접두는 탐지하지 않는다");
    }

    @Test
    @DisplayName("문맥 조건은 여전히 필수다")
    void bankContextIsStillRequired() {
        // 은행명도 거래 어휘도 없으면 자릿수가 맞아도 탐지하지 않는다.
        ScanResult r = scan("일련번호는 123-45-678901 입니다");
        assertFalse(has(r, "BANK_ACCOUNT"),
                "문맥 조건이 사라지면 주문번호·사번이 다시 밀려든다: " + summary(r));
    }

    // ============================================================
    // 알고 감수한 대가 — 다음 측정이 판단할 몫
    // ============================================================

    @Test
    @DisplayName("[알려진 한계] 공백으로 적은 계좌번호는 놓친다")
    void knownLimitation_spaceSeparatedAccountIsMissed() {
        // 공백 허용이 오탐의 주된 통로였으므로 맞바꾼 것이다.
        // 이 시험은 "고쳐야 할 결함"이 아니라 "알고 있는 현재 동작"을 적어 둔 것이다.
        // 실제로 문제가 되는지는 시험 문서(dlp-eval D02)를 넣어 다음 측정이 판단한다.
        assertFalse(has(scan("국민은행 123 45 678901 로 입금"), "BANK_ACCOUNT"),
                "이 시험이 실패한다면 공백 허용이 되살아난 것이다. "
                        + "그렇다면 C06(공백 건너뛰기 오탐)도 함께 확인할 것");
    }

    @Test
    @DisplayName("[알려진 한계] 4묶음 숫자 식별자는 아직 계좌번호와 구분되지 않는다")
    void knownLimitation_fourGroupIdentifiersStillMatch() {
        // 자릿수만으로는 농협식 계좌와 주문번호를 가를 수 없다. 은행 코드 대조가 필요하다.
        // 측정으로 확인된 오탐이 아니라 검토 중 떠올린 형태이므로, 추측으로 규칙을
        // 더 좁히지 않고 시험 문서(dlp-eval C07)로 남겨 다음 측정이 판단하게 한다.
        assertTrue(has(scan("입금 확인 주문번호 2024-1234-5678 참조"), "BANK_ACCOUNT"),
                "이 시험이 실패했다면 규칙이 더 좁혀진 것이다. "
                        + "그 자체는 개선일 수 있으나, 진양성(농협 4묶음)이 함께 죽지 않았는지 확인할 것");
    }
}
