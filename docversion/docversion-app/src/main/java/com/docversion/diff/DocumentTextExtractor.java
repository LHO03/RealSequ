package com.docversion.diff;

import com.docversion.domain.FileContent;

/**
 * 문서 텍스트 추출기. C++ DocumentTextExtractor의 인터페이스화.
 * <p>설계 의도(C++ 주석 유지): DLP에서 민감 정보가 "어떻게 변경되었는지" 추적하려면
 * 바이너리 문서(DOCX/HWPX/PDF)도 텍스트 수준 diff가 가능해야 함.
 * <p>이 인터페이스가 Nextcloud/외부 라이브러리가 꽂힐 seam.
 * 실제 구현은 유형 2(라이브러리로 해소): HWPX/DOCX → java.util.zip + XML 파서,
 * PDF → Apache PDFBox, 또는 Apache Tika 단일화.
 */
public interface DocumentTextExtractor {

    /** 해당 MIME 타입에서 텍스트 추출이 가능한지. */
    boolean canExtract(String mimeType);

    /**
     * 내용까지 보고 추출 가능 여부를 판단한다.
     *
     * <p><b>선언된 MIME만 믿으면 안 되는 이유.</b> 업로드 클라이언트가 붙이는
     * Content-Type은 신뢰할 수 없다. 실측으로 확인된 예: curl은 확장자 표에 없는
     * {@code .docx}에 {@code application/octet-stream}을 붙인다. 그 값을 그대로 믿으면
     * 멀쩡히 추출되는 Word 문서가 "지원하지 않는 바이너리"로 분류되어
     * <b>검사 자체가 이뤄지지 않는다</b>. 탐지율 측정에서 docx 15건이 전부
     * 판정 불가로 나온 원인이 이것이었다.
     *
     * <p>판정 불가는 "안전"이 아니라 "판정하지 못함"이므로 기능상 오동작은 아니지만,
     * 실무 문서의 상당수가 Word라는 점을 생각하면 유출 차단이 사실상 비어 있게 된다.
     *
     * <p>기본 구현은 선언된 MIME만 본다. 내용 기반 판별이 가능한 구현체가
     * 이 메서드를 재정의한다.
     */
    default boolean canExtract(FileContent content) {
        return content != null && canExtract(content.mimeType());
    }

    /** 텍스트 추출 시도. 실패/미지원 시 ExtractionResult.failed(). */
    DiffTypes.ExtractionResult extractText(FileContent content);
}
