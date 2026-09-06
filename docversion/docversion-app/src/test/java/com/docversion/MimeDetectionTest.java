package com.docversion;

import com.docversion.diff.TikaDocumentTextExtractor;
import com.docversion.domain.FileContent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 선언된 MIME을 믿지 않고 내용으로 형식을 판별하는지 확인한다. (RD-SRS-9.4 · 5.1)
 *
 * <p><b>왜 필요한가.</b> 업로드 클라이언트가 붙이는 Content-Type은 신뢰할 수 없다.
 * 실측: curl은 확장자 표에 없는 {@code .docx}에 {@code application/octet-stream}을 붙인다.
 * 그 값을 그대로 믿던 시절에는 멀쩡한 Word 문서가 "지원하지 않는 바이너리"로 분류되어
 * 텍스트 추출을 아예 시도하지 않았고, 결과적으로 <b>검사 자체가 이뤄지지 않았다</b>.
 *
 * <p>2026-08-31 탐지율 측정에서 docx 15건이 전부 판정 불가로 나왔고, 원인이 이것이었다.
 * 판정 불가는 "안전"이 아니라 "판정하지 못함"이므로 오답을 낸 것은 아니지만,
 * 실무 문서의 상당수가 Word라는 점에서 유출 차단이 사실상 비어 있는 상태였다.
 *
 * <p>스프링도 데이터베이스도 쓰지 않는다. 추출기 단독 시험이다.
 */
class MimeDetectionTest {

    private final TikaDocumentTextExtractor extractor = new TikaDocumentTextExtractor();

    // ------------------------------------------------------------
    // 1) 회귀의 핵심 — 범용 MIME으로 선언된 문서
    // ------------------------------------------------------------

    @Test
    void genericMimeOnRealDocument_isDetectedByContent() {
        // 최소한의 PDF. 앞부분 시그니처만으로 형식이 판별된다.
        byte[] pdf = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< >>\n%%EOF\n"
                .getBytes(StandardCharsets.US_ASCII);

        assertThat(extractor.canExtract("application/octet-stream"))
                .as("전제: 선언된 MIME만 보면 판별할 수 없다")
                .isFalse();

        assertThat(extractor.canExtract(new FileContent(pdf, "application/octet-stream")))
                .as("선언을 믿을 수 없으면 내용을 보고 판별해야 한다. "
                        + "여기가 false로 돌아가면 docx·pdf가 통째로 판정 불가가 된다")
                .isTrue();
    }

    @Test
    void officeOpenXmlDeclaredAsOctetStream_isDetectedByContent() {
        assertThat(extractor.canExtract(new FileContent(minimalDocx(), "application/octet-stream")))
                .as("curl이 .docx에 붙이는 값이 정확히 이것이다")
                .isTrue();
    }

    // ------------------------------------------------------------
    // 2) 정상적으로 선언된 값은 그대로 존중한다
    // ------------------------------------------------------------

    @Test
    void informativeMime_isUsedAsDeclared() {
        byte[] anything = new byte[]{1, 2, 3, 4};

        assertThat(extractor.canExtract(new FileContent(anything, "application/pdf")))
                .as("판별 비용을 매번 치르지 않는다. 선언이 쓸 만하면 그대로 쓴다")
                .isTrue();
        assertThat(extractor.canExtract(new FileContent(anything, "image/png")))
                .as("선언된 값이 명확히 미지원이면 내용을 보지 않고 거른다")
                .isFalse();
    }

    // ------------------------------------------------------------
    // 3) 정말로 추출 대상이 아닌 것은 여전히 걸러진다
    // ------------------------------------------------------------

    @Test
    void genuineBinary_isStillRejected() {
        // PNG 시그니처. 형식은 판별되지만 텍스트 추출 대상은 아니다.
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

        assertThat(extractor.canExtract(new FileContent(png, "application/octet-stream")))
                .as("내용을 본다고 해서 아무거나 추출 대상이 되어서는 안 된다")
                .isFalse();
    }

    @Test
    void emptyContent_isRejectedWithoutDetection() {
        assertThat(extractor.canExtract(new FileContent(new byte[0], "application/octet-stream")))
                .isFalse();
        assertThat(extractor.canExtract((FileContent) null))
                .isFalse();
    }

    // ------------------------------------------------------------

    /**
     * Tika가 Office OpenXML로 인식할 최소 구조의 zip.
     *
     * <p>실제 Word 파일을 시험 자원으로 두지 않는 이유는, 바이너리 고정물이
     * 저장소에 쌓이면 무엇이 왜 들어 있는지 추적이 어려워지기 때문이다.
     * 판별에 필요한 것은 {@code [Content_Types].xml}의 선언부뿐이다.
     */
    private static byte[] minimalDocx() {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out)) {

            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write(("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/word/document.xml" ContentType=\
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <w:document xmlns:w=\
                    "http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body><w:p><w:r><w:t>본문</w:t></w:r></w:p></w:body>
                    </w:document>
                    """).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.finish();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("시험용 docx 생성 실패", e);
        }
    }
}
