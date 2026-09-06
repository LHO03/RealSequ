package com.docversion.dlp.scan;

import com.docversion.dlp.api.Finding;
import com.docversion.dlp.api.ScanRequest;
import com.docversion.dlp.api.ScanResult;
import com.docversion.dlp.api.SensitiveDataScanner;
import com.docversion.dlp.mask.Masker;
import com.docversion.dlp.rule.PatternRule;
import com.docversion.dlp.rule.RuleProvider;
import com.docversion.dlp.validate.Validator;
import com.docversion.dlp.validate.Validators;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;

/**
 * 정규식 규칙 기반 민감 데이터 탐지기. (RD-SRS-5.1, 5.2, 5.4)
 *
 * <p>세 요구사항을 하나의 엔진으로 처리한다. 5.4가 탐지 능력 자체이고,
 * 5.1과 5.2는 그 엔진에 무엇을 입력하느냐의 차이일 뿐이다.
 * 5.1은 버전 전체 텍스트를, 5.2는 버전 비교가 산출한 추가된 줄을 넣는다.
 * 별도 로직을 두지 않는 이유가 이것이다.
 *
 * <p>처리 순서:
 * <ol>
 *   <li>활성 규칙을 순회하며 정규식 매칭</li>
 *   <li>문맥 조건이 있으면 매칭 주변을 확인 (계좌번호의 은행명 등)</li>
 *   <li>검증기가 있으면 실행하여 점수를 결정 (통과 시 scoreVerified)</li>
 *   <li>원문을 마스킹하여 Finding 생성</li>
 *   <li>점수를 합산해 임계값과 비교</li>
 * </ol>
 *
 * <p>이 클래스는 상태를 갖지 않는다. 규칙은 공급자에서 매 호출 시 받아오며,
 * 공급자 구현체가 캐시를 책임진다. 배경 작업자가 동시에 호출하므로
 * 인스턴스 필드에 검사 중간 상태를 두어서는 안 된다.
 */
public final class RuleBasedScanner implements SensitiveDataScanner {

    public static final String NAME = "RULE";

    /**
     * 한 규칙이 한 문서에서 만들어낼 수 있는 탐지 항목 수의 안전 상한.
     *
     * <p>점수 상한(max_hits_scored)과는 다른 목적이다. 점수는 무제한 합산하되,
     * 병리적인 입력(예: 이메일 주소 수십만 개가 든 파일)에서 메모리가
     * 폭주하는 것만 막는다. 초과분은 개수만 note에 기록한다.
     */
    private static final int MAX_FINDINGS_PER_RULE = 10_000;

    /**
     * 검사 대상 텍스트 길이 상한.
     *
     * <p>성능평가지표가 100MB 문서를 전제하므로, 추출된 텍스트도 수십 MB에
     * 이를 수 있다. 정규식 역추적 비용이 길이에 비례하므로 상한을 둔다.
     * 초과 시 앞부분만 검사하고 그 사실을 결과에 남긴다. 잘라냈다는 사실을
     * 감추면 검사되지 않은 뒷부분이 안전한 것처럼 보이게 된다.
     */
    private static final int MAX_TEXT_CHARS = 5_000_000;

    private final RuleProvider ruleProvider;

    public RuleBasedScanner(RuleProvider ruleProvider) {
        this.ruleProvider = Objects.requireNonNull(ruleProvider, "ruleProvider");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ScanResult scan(ScanRequest request) {
        // 계약상 예외를 밖으로 던지지 않는다. 탐지 실패가 버전 생성이나
        // 다른 검사기를 중단시켜서는 안 되므로 UNDETERMINED로 감싼다.
        try {
            return doScan(request);
        } catch (RuntimeException e) {
            return ScanResult.undetermined(NAME,
                    "탐지 중 오류: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    private ScanResult doScan(ScanRequest request) {
        if (request == null) {
            return ScanResult.undetermined(NAME, "요청이 없습니다");
        }

        List<PatternRule> rules = ruleProvider.activeRules();
        if (rules == null || rules.isEmpty()) {
            // 규칙이 하나도 없으면 "민감하지 않음"이 아니라 판정 불가다.
            // 규칙 적재 실패를 안전으로 오인하면 전 문서가 통과해버린다.
            return ScanResult.undetermined(NAME, "활성 규칙이 없습니다");
        }

        String text = request.text();
        StringBuilder note = new StringBuilder();

        if (text.length() > MAX_TEXT_CHARS) {
            note.append("텍스트가 길어 앞 ").append(MAX_TEXT_CHARS)
                .append("자만 검사했습니다(전체 ").append(text.length()).append("자). ");
            text = text.substring(0, MAX_TEXT_CHARS);
        }

        List<Finding> findings = new ArrayList<>();
        for (PatternRule rule : rules) {
            collectFindings(rule, text, findings, note);
        }
        findings = resolveOverlaps(findings);

        ScanResult result = ScanResult.of(findings, ruleProvider.threshold(), NAME);
        if (note.length() == 0) {
            return result;
        }
        // note가 있는 경우에도 판정 자체는 유지한다.
        return new ScanResult(result.verdict(), result.totalScore(), result.threshold(),
                result.findings(), NAME, note.toString().trim());
    }

    /** 규칙 하나를 텍스트 전체에 적용해 탐지 항목을 모은다. */
    private void collectFindings(PatternRule rule, String text,
                                 List<Finding> out, StringBuilder note) {
        Validator validator = rule.hasValidator() ? Validators.find(rule.validatorName()) : null;
        if (rule.hasValidator() && validator == null) {
            // 알 수 없는 검증기가 지정된 경우. 규칙을 버리지 않고 검증만 건너뛴다.
            // 기본 점수(score)가 적용되므로 탐지 자체는 유지된다.
            note.append("규칙 '").append(rule.name())
                .append("'의 검증기 '").append(rule.validatorName())
                .append("'를 찾을 수 없어 기본 점수를 적용했습니다. ");
        }

        Matcher m = rule.pattern().matcher(text);
        int hits = 0;
        int scoredHits = 0;
        int skipped = 0;

        while (m.find()) {
            if (hits >= MAX_FINDINGS_PER_RULE) {
                skipped++;
                continue;
            }

            String matched = m.group();
            int start = m.start();
            int length = m.end() - start;
            if (length <= 0) {
                continue;   // 빈 매칭 방어. 잘못 작성된 정규식이 무한 루프를 만들지 않도록
            }

            // 문맥 조건: 매칭 주변에 지정된 어휘가 있어야 인정한다.
            // 계좌번호가 대표적이다. 자릿수만 보면 주문번호·사번이 모두 걸린다.
            if (rule.hasContextCondition() && !contextMatches(rule, text, start, m.end())) {
                continue;
            }

            hits++;

            boolean verified = validator != null && validator.isValid(matched);
            int score = verified ? rule.scoreVerified() : rule.score();

            // 점수 상한. 0이면 무제한이며 현재 모든 규칙이 무제한이다(V16).
            // 판정이 임계값 초과 여부만 보는 이진 판단이므로 상한은 결과를 바꾸지 않고,
            // 오히려 대량 유출 문서와 소량 문서의 구분을 지운다.
            if (rule.maxHitsScored() > 0 && scoredHits >= rule.maxHitsScored()) {
                score = 0;
            } else if (score > 0) {
                scoredHits++;
            }

            out.add(new Finding(
                    rule.name(),
                    rule.severity(),
                    score,
                    start,
                    length,
                    Masker.mask(matched, rule.maskKeepPrefix(), rule.maskKeepSuffix()),
                    verified));
        }

        if (skipped > 0) {
            note.append("규칙 '").append(rule.name()).append("' 탐지가 상한(")
                .append(MAX_FINDINGS_PER_RULE).append("건)을 넘어 ")
                .append(skipped).append("건을 생략했습니다. ");
        }
    }

    /**
     * 같은 문자열 구간을 여러 규칙이 잡은 경우 하나만 남긴다.
     *
     * <p>규칙들의 정규식은 서로 독립적으로 작성되므로 구간이 겹칠 수 있다.
     * 실제로 휴대전화번호 {@code 010-1234-5678}은 계좌번호 규칙
     * {@code \d{2,6}-\d{2,6}-\d{2,6}}에도 부합하며, 근처에 은행명이 있으면
     * 문맥 조건까지 통과해 한 문자열이 두 번 집계된다(80 + 20 = 100점).
     *
     * <p>이는 점수를 부풀릴 뿐 아니라 탐지 목록을 혼란스럽게 만든다.
     * 전화번호가 계좌번호로 보고되면 담당자가 판단을 그르칠 수 있다.
     *
     * <p><b>해소 기준은 점수다.</b> 점수가 같으면 더 긴 매칭을, 그것도 같으면
     * 위치와 규칙 이름 순으로 정해 결과가 항상 같도록 한다(같은 입력에 같은 출력).
     * 점수가 높은 쪽이 대체로 더 구체적인 규칙이므로 합리적인 기준이다.
     *
     * <p><b>종전에는 이 문단과 코드가 어긋나 있었다.</b> 정렬 1차 키가 점수가 아니라
     * 위치여서, 문서에서 먼저 나온 항목이 점수와 무관하게 이겼다. 그 결과 실제로
     * 이런 일이 일어났다(규칙 5종으로 재현됨).
     *
     * <pre>
     *   "계좌 이체 예정 1002-345 900101-1000006 확인"
     *     계좌번호  '1002-345 900101'  80점  위치 10   ← 먼저 나와서 채택
     *     주민번호  '900101-1000006'  100점  위치 18   ← 겹친다는 이유로 버려짐
     * </pre>
     *
     * 검증까지 통과한 주민등록번호가 사라지고, 담당자 화면에는 계좌번호 하나만
     * 남는다. 판정은 어차피 임계값을 넘으므로 <b>지표에는 드러나지 않고</b>
     * 탐지 목록만 조용히 틀린다. 바로 위 문단이 경계하는 상황 그대로다.
     *
     * <p>겹침 검사는 채택된 구간을 시작 위치로 정렬해 두고 후보의 바로 앞뒤 구간만
     * 본다. 채택된 구간끼리는 서로 겹치지 않으므로 그 둘만 확인하면 충분하며,
     * 우선순위 순회와 위치 검색을 분리할 수 있다.
     *
     * <p>구간이 부분적으로만 겹치는 경우에도 하나만 남긴다. 한 문자열에
     * 두 종류의 민감정보가 실제로 겹쳐 있는 경우는 드물다.
     */
    private List<Finding> resolveOverlaps(List<Finding> findings) {
        if (findings.size() < 2) {
            return findings;
        }

        // 우선순위 순서. 점수가 1차 키다.
        List<Finding> byPriority = new ArrayList<>(findings);
        byPriority.sort(Comparator
                .<Finding>comparingInt(Finding::score).reversed()
                .thenComparing(Comparator.<Finding>comparingInt(Finding::length).reversed())
                .thenComparing(Comparator.comparingInt(Finding::offset))
                .thenComparing(Finding::patternName));

        // 채택된 구간을 시작 위치로 정렬해 보관한다.
        TreeMap<Integer, Finding> accepted = new TreeMap<>();
        for (Finding candidate : byPriority) {
            Map.Entry<Integer, Finding> before = accepted.floorEntry(candidate.offset());
            if (before != null && intersects(before.getValue(), candidate)) {
                continue;
            }
            Map.Entry<Integer, Finding> after = accepted.ceilingEntry(candidate.offset());
            if (after != null && intersects(after.getValue(), candidate)) {
                continue;
            }
            accepted.put(candidate.offset(), candidate);
        }

        // 결과는 문서 순서로 돌려준다. 화면과 저장 순서가 본문을 따라가는 편이 읽기 쉽다.
        return new ArrayList<>(accepted.values());
    }

    private static boolean intersects(Finding a, Finding b) {
        int aEnd = a.offset() + a.length();
        int bEnd = b.offset() + b.length();
        return a.offset() < bEnd && b.offset() < aEnd;
    }

    /**
     * 매칭 위치 앞뒤 지정 범위에 문맥 조건이 나타나는지 확인한다.
     *
     * <p>범위를 매칭 자체까지 포함해 잡으면 숫자열 안의 문자가 조건을
     * 만족시킬 수 있으므로, 앞뒤 구간을 각각 확인한다.
     */
    private boolean contextMatches(PatternRule rule, String text, int start, int end) {
        int window = rule.contextWindow();
        int from = Math.max(0, start - window);
        int to = Math.min(text.length(), end + window);

        String before = text.substring(from, start);
        String after = text.substring(end, to);

        return rule.contextPattern().matcher(before).find()
                || rule.contextPattern().matcher(after).find();
    }
}
