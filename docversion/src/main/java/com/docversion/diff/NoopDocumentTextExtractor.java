package com.docversion.diff;

import com.docversion.domain.FileContent;

/**
 * 텍스트 추출기 기본 stub. 현재 어떤 바이너리도 추출하지 않으므로
 * 바이너리 diff는 HASH_ONLY로 fallback(C++ 현재 동작과 동일 — extractor가 stub).
 * <p>알파 이후 Tika/PDFBox 구현체를 만들어 이 빈을 대체하면 TEXT_EXTRACTED 경로가 활성화됨.
 * (교체 시 이 클래스 삭제 후 @Component 구현체 추가만 하면 됨 — DiffService는 무수정)
 */
// @Component 제거: TikaDocumentTextExtractor(실구현)로 대체됨.
// 파일은 "추출기 seam의 최소 구현" 예시 기록용으로 남겨 둔다 (테스트에서 수동 생성 가능).
public class NoopDocumentTextExtractor implements DocumentTextExtractor {

    @Override
    public boolean canExtract(String mimeType) {
        return false; // TODO(alpha+): Tika/PDFBox 연동 시 지원 MIME 목록 반환
    }

    @Override
    public DiffTypes.ExtractionResult extractText(FileContent content) {
        return DiffTypes.ExtractionResult.failed();
    }
}
