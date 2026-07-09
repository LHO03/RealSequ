package com.docversion.diff;

import com.docversion.domain.FileContent;
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

    /** 추출 텍스트 상한(문자 수) — 초대형 문서로 인한 메모리 폭주 방지. */
    private static final int MAX_CHARS = 5_000_000;

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
        try (ByteArrayInputStream in = new ByteArrayInputStream(content.data())) {
            BodyContentHandler handler = new BodyContentHandler(MAX_CHARS);
            Metadata metadata = new Metadata();
            parser.parse(in, handler, metadata, new ParseContext());
            String text = handler.toString();
            if (text == null || text.isBlank()) {
                // 파싱은 됐지만 글자가 없음(스캔 PDF 등) → 폴백이 정직
                log.debug("[텍스트 추출] 본문 없음 (mime={}) — HASH_ONLY 폴백", content.mimeType());
                return DiffTypes.ExtractionResult.failed();
            }
            return new DiffTypes.ExtractionResult(true, text);
        } catch (Exception e) {
            // 손상 파일·암호화 문서·한도 초과 등 — 실패 반환하면 DiffService가 HASH_ONLY 폴백
            log.info("[텍스트 추출] 실패 (mime={}): {}", content.mimeType(), e.getMessage());
            return DiffTypes.ExtractionResult.failed();
        }
    }
}
