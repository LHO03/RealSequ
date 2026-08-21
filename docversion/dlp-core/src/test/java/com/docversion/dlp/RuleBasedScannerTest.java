package com.docversion.dlp;

import com.docversion.dlp.api.*;
import com.docversion.dlp.rule.DefaultRules;
import com.docversion.dlp.rule.StaticRuleProvider;
import com.docversion.dlp.scan.HeuristicScanner;
import com.docversion.dlp.scan.NoopScanner;
import com.docversion.dlp.scan.RuleBasedScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 탐지 엔진 시험. (RD-SRS-5.1, 5.2, 5.4)
 *
 * <p>dlp-core는 의존성이 없으므로 Spring 문맥이나 데이터베이스 없이
 * 순수 단위 시험으로 전부 검증된다. 선행 산출물은 규칙 적재 계층이
 * 빠져 컴파일조차 되지 않았는데, 그 계층을 모듈 밖으로 밀어낸 덕분에
 * 엔진은 언제나 단독으로 시험 가능하다.
 */
class RuleBasedScannerTest {

    private final SensitiveDataScanner scanner =
            new RuleBasedScanner(DefaultRules.provider());

    private ScanResult scan(String text) {
        return scanner.scan(ScanRequest.full("f1", "v1", text, "text/plain"));
    }

    // ------------------------------------------------------------
    // RD-SRS-5.1 저장 문서의 자동 판별
    // ------------------------------------------------------------

    @Test
    @DisplayName("5.1 인사기록 형태 문서에서 여러 유형을 동시에 탐지한다")
    void detectsMultipleTypesInRealisticDocument() {
        ScanResult r = scan("""
                인사기록 카드
                성명: 홍길동
                주민등록번호: 010203-4567890
                연락처: 010-1234-5678
                이메일: hong@example.com
                급여계좌: 국민은행 123-45-678901
                """);

        assertEquals(ScanVerdict.SENSITIVE, r.verdict());
        assertEquals(Severity.HIGH, r.highestSeverity());
        assertTrue(r.findings().size() >= 4, "최소 4종이 탐지되어야 함");
    }

    @Test
    @DisplayName("5.1 민감 정보가 없는 문서는 민감하지 않음으로 판정한다")
    void cleanDocumentIsNotSensitive() {
        ScanResult r = scan("이 문서는 회의록입니다. 다음 회의는 3월 5일 오후 2시입니다.");

        assertEquals(ScanVerdict.NOT_SENSITIVE, r.verdict());
        assertEquals(0, r.totalScore());
        assertTrue(r.findings().isEmpty());
    }

    // ------------------------------------------------------------
    // RD-SRS-5.2 변경분 판별
    // ------------------------------------------------------------

    @Test
    @DisplayName("5.2 변경분 검사도 같은 엔진으로 동작한다")
    void deltaScopeUsesSameEngine() {
        ScanResult r = scanner.scan(
                ScanRequest.delta("f1", "v2", "+ 주민등록번호: 010203-4567890", "text/plain"));

        assertEquals(ScanVerdict.SENSITIVE, r.verdict());
    }

    // ------------------------------------------------------------
    // 점수제 판정
    // ------------------------------------------------------------

    @Test
    @DisplayName("전화번호 2건은 40점으로 임계값 미달")
    void twoPhonesBelowThreshold() {
        ScanResult r = scan("연락처 010-1111-2222, 010-3333-4444");

        assertEquals(40, r.totalScore());
        assertEquals(ScanVerdict.NOT_SENSITIVE, r.verdict());
    }

    @Test
    @DisplayName("전화번호 3건은 60점으로 임계값 초과")
    void threePhonesExceedThreshold() {
        ScanResult r = scan("연락처 010-1111-2222, 010-3333-4444, 010-5555-6666");

        assertEquals(60, r.totalScore());
        assertEquals(ScanVerdict.SENSITIVE, r.verdict());
    }

    @Test
    @DisplayName("점수 상한이 없으므로 대량 문서는 점수가 그만큼 누적된다")
    void scoreAccumulatesWithoutCap() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("010-1234-").append(String.format("%04d", i)).append('\n');
        }
        ScanResult r = scan(sb.toString());

        assertEquals(1000, r.totalScore(),
                "상한을 두면 소량 문서와 대량 명부가 구분되지 않는다");
    }

    // ------------------------------------------------------------
    // 검증기 (체크섬 이원화)
    // ------------------------------------------------------------

    @Test
    @DisplayName("체크섬을 통과한 주민번호는 100점")
    void ssnWithValidChecksumScoresHigher() {
        ScanResult r = scan("주민등록번호 010203-4567890");

        assertEquals(100, r.totalScore());
        assertTrue(r.findings().get(0).verified());
    }

    @Test
    @DisplayName("체크섬이 어긋난 주민번호도 60점으로 여전히 민감 판정")
    void ssnWithInvalidChecksumStillDetected() {
        ScanResult r = scan("주민등록번호 861203-1234567");

        assertEquals(60, r.totalScore());
        assertFalse(r.findings().get(0).verified());
        assertEquals(ScanVerdict.SENSITIVE, r.verdict(),
                "오타가 섞인 실제 주민번호를 놓치면 유출 차단이 무력화된다");
    }

    // ------------------------------------------------------------
    // 문맥 조건 (과탐 억제)
    // ------------------------------------------------------------

    @Test
    @DisplayName("은행명이 있으면 계좌번호로 탐지한다")
    void bankAccountDetectedWithContext() {
        ScanResult r = scan("국민은행 123-45-678901 로 입금 바랍니다");

        assertTrue(r.findings().stream()
                .anyMatch(f -> f.patternName().equals("BANK_ACCOUNT")));
    }

    @Test
    @DisplayName("문맥이 없으면 계좌번호로 보지 않는다")
    void orderNumberNotDetectedAsBankAccount() {
        ScanResult r = scan("주문번호 202401-15-001 을 확인하세요");

        assertTrue(r.findings().stream()
                .noneMatch(f -> f.patternName().equals("BANK_ACCOUNT")));
    }

    @Test
    @DisplayName("휴대전화번호를 계좌번호로 잘못 분류하지 않는다")
    void phoneIsNotMisclassifiedAsBankAccount() {
        ScanResult r = scan("연락처: 010-1234-5678\n급여계좌: 국민은행 123-45-678901");

        List<String> names = r.findings().stream().map(Finding::patternName).toList();
        assertEquals(1, names.stream().filter(n -> n.equals("PHONE")).count());
        assertEquals(1, names.stream().filter(n -> n.equals("BANK_ACCOUNT")).count());
        assertEquals(100, r.totalScore(), "이중 집계되면 180점이 된다");
    }

    // ------------------------------------------------------------
    // 보안 — 원문 미노출
    // ------------------------------------------------------------

    @Test
    @DisplayName("탐지 결과에 원문이 남지 않는다")
    void findingsDoNotLeakRawValues() {
        ScanResult r = scan("주민등록번호 010203-4567890, 카드 4539578763621486");

        for (Finding f : r.findings()) {
            assertFalse(f.maskedValue().contains("4567890"),
                    "주민번호 뒷자리가 노출되었다: " + f.maskedValue());
            assertFalse(f.maskedValue().contains("8763621"),
                    "카드 중간자리가 노출되었다: " + f.maskedValue());
        }
    }

    @Test
    @DisplayName("탐지 위치가 원문 구간과 일치한다")
    void offsetAndLengthPointToOriginalSpan() {
        String text = "앞부분 텍스트 010203-4567890 뒷부분";
        ScanResult r = scan(text);

        Finding f = r.findings().get(0);
        assertEquals("010203-4567890",
                text.substring(f.offset(), f.offset() + f.length()));
    }

    // ------------------------------------------------------------
    // 예외 안전성
    // ------------------------------------------------------------

    @Test
    @DisplayName("규칙이 없으면 안전이 아니라 판정 불가를 반환한다")
    void noRulesYieldsUndetermined() {
        SensitiveDataScanner s =
                new RuleBasedScanner(new StaticRuleProvider(List.of(), 50));

        ScanResult r = s.scan(ScanRequest.full("f", "v", "010203-4567890", null));

        assertEquals(ScanVerdict.UNDETERMINED, r.verdict(),
                "규칙 적재 실패를 안전으로 오인하면 전 문서가 통과한다");
        assertFalse(r.isConclusive());
    }

    @Test
    @DisplayName("빈 텍스트에도 예외를 던지지 않는다")
    void emptyTextIsHandled() {
        ScanResult r = scan("");

        assertEquals(ScanVerdict.NOT_SENSITIVE, r.verdict());
    }

    // ------------------------------------------------------------
    // 골격 검사기
    // ------------------------------------------------------------

    @Test
    @DisplayName("NoopScanner는 판정 불가를 반환한다")
    void noopScannerIsUndetermined() {
        ScanResult r = new NoopScanner()
                .scan(ScanRequest.full("f", "v", "010203-4567890", null));

        assertEquals(ScanVerdict.UNDETERMINED, r.verdict(),
                "실수로 배선되어도 전 문서가 안전으로 표시되어서는 안 된다");
    }

    @Test
    @DisplayName("HeuristicScanner(5.5)는 이번 범위 밖이므로 판정에 참여하지 않는다")
    void heuristicScannerIsOutOfScope() {
        ScanResult r = new HeuristicScanner()
                .scan(ScanRequest.full("f", "v", "대외비 기밀 문서", null));

        assertEquals(ScanVerdict.UNDETERMINED, r.verdict());
    }
}
