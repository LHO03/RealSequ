package com.docversion.diff;

import com.docversion.domain.FileContent;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.detect.Detector;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.Set;

/**
 * Apache Tika 기반 텍스트 추출기 (RD-SRS-9.4 — 바이너리 문서의 텍스트 diff).
 *
 * <p>NoopDocumentTextExtractor(stub)를 대체하는 실구현. PDF·Word(DOC/DOCX)·
 * Excel·PowerPoint·OpenDocument·RTF에서 본문 텍스트를 추출해, DiffService의
 * TEXT_EXTRACTED 경로(줄 단위 Myers diff)를 활성화한다.
 *
 * <p><b>미지원 형식은 정직하게 실패</b> — canExtract가 false를 반환하면 DiffService가
 * HASH_ONLY(내용 지문 비교)로 안전하게 폴백한다. 잘못 추출된 텍스트로 엉뚱한 diff를
 * 보여주는 것보다 "비교 불가"가 낫다는 원칙.
 *
 * <p><b>HWP는 의도적 미지원(지원 예정)</b>: 한국 고유 형식이라 Tika 커버리지가 약하다.
 * 추후 hwplib 등 전용 라이브러리 기반 구현체를 이 인터페이스에 추가하면 된다
 * (DiffService 무수정 — seam 유지).
 *
 * <p>스캔 이미지형 PDF처럼 파싱은 성공했지만 글자가 없는 경우도 실패로 처리한다
 * (빈 텍스트끼리의 diff는 "차이 없음"이라는 잘못된 결론을 낳으므로).
 */
@Component
@Primary
public class TikaDocumentTextExtractor implements DocumentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(TikaDocumentTextExtractor.class);

    /**
     * 추출 텍스트 상한(문자 수) — 초대형 문서로 인한 메모리 폭주를 막는 안전 밸브.
     *
     * <p><b>탐지 엔진의 상한(5,000,000자)보다 높게 잡는다.</b> 두 상한이 같으면
     * 잘렸다는 사실이 어디에도 남지 않는다 — 추출기가 정확히 상한만큼 돌려주고,
     * 엔진은 "상한을 넘었는지"를 초과 여부로 판단하므로 같은 값에서는 알아채지 못한다.
     *
     * <p>추출기를 넉넉히 두면 자르는 일과 그 사실을 기록하는 일이 모두 엔진 한 곳에서
     * 일어난다. 여기의 상한은 그보다 훨씬 큰 문서에 대한 마지막 방어선일 뿐이다.
     */
    private static final int DEFAULT_MAX_CHARS = 10_000_000;

    /** 지원 MIME (정확 일치). */
    private static final Set<String> EXACT = Set.of(
            "application/pdf",
            "application/msword",                     // .doc
            "application/vnd.ms-excel",               // .xls
            "application/vnd.ms-powerpoint",          // .ppt
            "application/rtf",
            "text/rtf"
    );

    /** 지원 MIME (접두 일치) — Office OpenXML(.docx/.xlsx/.pptx), OpenDocument(.odt/.ods/.odp) */
    private static final Set<String> PREFIX = Set.of(
            "application/vnd.openxmlformats-officedocument.",
            "application/vnd.oasis.opendocument."
    );

    private final AutoDetectParser parser = new AutoDetectParser();

    /** 추출 텍스트 상한. 시험에서 작은 값으로 바꿔 상한 동작을 확인한다. */
    private final int maxChars;

    public TikaDocumentTextExtractor() {
        this(DEFAULT_MAX_CHARS);
    }

    TikaDocumentTextExtractor(int maxChars) {
        this.maxChars = maxChars;
    }

    /** 내용 기반 형식 판별기. 선언된 MIME을 믿을 수 없을 때 쓴다. */
    private final Detector detector = new DefaultDetector();

    /**
     * 선언된 MIME이 "아무것도 말해주지 않는" 값인지.
     *
     * <p>업로드 클라이언트가 확장자를 모르면 대개 이 값들을 붙인다.
     * 그때는 선언을 버리고 내용을 직접 본다.
     */
    private static boolean isUninformative(String mime) {
        if (mime == null || mime.isBlank()) {
            return true;
        }
        String m = mime.toLowerCase(Locale.ROOT).split(";")[0].trim();
        return m.equals("application/octet-stream")
                || m.equals("application/binary")
                || m.equals("binary/octet-stream")
                || m.equals("content/unknown");
    }

    /**
     * 내용을 보고 추출 가능 여부를 판단한다. (RD-SRS-9.4 · 5.1)
     *
     * <p>선언된 MIME이 쓸 만하면 그대로 쓰고, 범용 값이면 Tika에게 형식을 물어본다.
     * 선언을 언제나 무시하지 않는 이유는 판별에도 비용이 들고, 정상적으로 선언된
     * 값이 대개 더 정확하기 때문이다.
     */
    @Override
    public boolean canExtract(FileContent content) {
        if (content == null || content.data() == null || content.data().length == 0) {
            return false;
        }
        if (!isUninformative(content.mimeType())) {
            return canExtract(content.mimeType());
        }
        String detected = detect(content);
        if (detected == null) {
            return false;
        }
        log.debug("[텍스트 추출] 선언 MIME({})을 신뢰할 수 없어 내용으로 판별: {}",
                content.mimeType(), detected);
        return canExtract(detected);
    }

    /** 내용 앞부분을 보고 형식을 판별한다. 실패하면 null. */
    private String detect(FileContent content) {
        try (java.io.InputStream in = TikaInputStream.get(content.data())) {
            return detector.detect(in, new Metadata()).toString();
        } catch (Exception e) {
            log.debug("[텍스트 추출] 형식 판별 실패: {}", e.toString());
            return null;
        }
    }

    @Override
    public boolean canExtract(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return false;
        }
        String m = mimeType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        if (EXACT.contains(m)) {
            return true;
        }
        for (String p : PREFIX) {
            if (m.startsWith(p)) {
                return true;
            }
        }
        // HWP 계열(application/x-hwp, application/haansofthwp, application/vnd.hancom.*)은
        // 명시적 미지원 — HASH_ONLY 폴백. (지원 예정: hwplib 기반 구현체 추가)
        return false;
    }

    @Override
    public DiffTypes.ExtractionResult extractText(FileContent content) {
        // handler를 try 밖에 두는 것이 이 메서드의 요점이다.
        // 길이 상한에 걸리면 Tika가 예외를 던지는데, 그때도 handler에는 이미 읽어낸
        // 앞부분이 그대로 담겨 있다. 예외만 보고 버리면 그 내용까지 함께 사라진다.
        BodyContentHandler handler = new BodyContentHandler(maxChars);

        try (ByteArrayInputStream in = new ByteArrayInputStream(content.data())) {
            parser.parse(in, handler, new Metadata(), new ParseContext());
        } catch (Exception e) {
            String partial = handler.toString();
            if (partial != null && !partial.isBlank()) {
                // 길이 상한에 걸린 경우가 대부분이다.
                //
                // 종전에는 여기서 실패로 처리해 긴 문서가 통째로 '판정 불가'가 되었다.
                // 그런데 탐지 엔진(RuleBasedScanner)은 같은 상황에서 앞부분만 검사하고
                // 그 사실을 결과에 남긴다. 두 곳의 정책이 어긋나 있었던 셈이다.
                //
                // 앞부분이라도 검사하는 편이 낫다. 잘렸다는 사실은 엔진 쪽 상한(더 낮게
                // 잡혀 있다)에 걸리면서 검사 결과의 note에 남는다.
                log.info("[텍스트 추출] 상한({}자)에 걸려 앞부분 {}자만 사용합니다 (mime={}): {}",
                        maxChars, partial.length(), content.mimeType(), e.getMessage());
                return new DiffTypes.ExtractionResult(true, partial);
            }
            // 손상 파일·암호화 문서 등 — 건질 것이 없다. DiffService가 HASH_ONLY로 폴백한다.
            log.info("[텍스트 추출] 실패 (mime={}): {}", content.mimeType(), e.getMessage());
            return DiffTypes.ExtractionResult.failed();
        }

        String text = handler.toString();
        if (text == null || text.isBlank()) {
            // 파싱은 됐지만 글자가 없음(스캔 PDF 등) → 폴백이 정직
            log.debug("[텍스트 추출] 본문 없음 (mime={}) — HASH_ONLY 폴백", content.mimeType());
            return DiffTypes.ExtractionResult.failed();
        }
        return new DiffTypes.ExtractionResult(true, text);
    }
}
