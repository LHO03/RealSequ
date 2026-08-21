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

    /** 텍스트 추출 시도. 실패/미지원 시 ExtractionResult.failed(). */
    DiffTypes.ExtractionResult extractText(FileContent content);
}
