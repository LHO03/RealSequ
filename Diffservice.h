// DiffService.h
// DocumentVersionWorkflowAPI - Diff 서비스 모듈
// 분리 목적: DiffService는 독립 기능 단위이므로 별도 헤더로 관리
//           라이브러리 배포 시 diff 기능만 개별 사용 가능
//           Java 전환 시 별도 클래스 파일로 대응
//
// 포함 내용:
//   - DiffLineType, DiffLine, DiffHunk (diff 결과 구조체)
//   - DiffMethod, DiffResult (diff 결과 + 방식 표시)
//   - DocumentType (파일 유형 분류)
//   - DocumentTextExtractor (문서 텍스트 추출 인터페이스)
//   - DiffService (핵심 diff 엔진: Myers diff + SHA-256)
//
// 의존: FileContent 구조체가 이 헤더를 include하기 전에 정의되어 있어야 함
//
// 변경 이력:
//   02/11 - 구조체 정의
//   03/05 - 전체 구현: Myers diff + SHA-256
//   03/13 - 재통합 + prepareVersionComparison 연동
//   03/18 - FNV-1a 제거 (SHA-256 일원화)
//         - 바이너리 diff 확장 (DocumentTextExtractor)
//         - 헤더 파일 분리

#pragma once

#include <string>
#include <vector>
#include <optional>
#include <cstdint>
#include <sstream>
#include <iomanip>
#include <algorithm>

// ==========================================
// 02/11 - DiffService 관련 구조체 새로 정의
// ==========================================

// 개별 변경 줄의 유형
// GitHub diff 뷰에서 녹색(+), 빨간색(-), 회색(변경 없음)에 대응
enum class DiffLineType {
    ADDED,      // 새로 추가된 줄 (GitHub의 녹색 "+")
    DELETED,    // 삭제된 줄 (GitHub의 빨간색 "-")
    UNCHANGED   // 변경 없는 줄 (컨텍스트 표시용)
};

// 개별 변경 줄 정보
// diff 결과의 최소 단위로, 하나의 줄이 추가/삭제/유지 중 어떤 상태인지를 나타냄
struct DiffLine {
    DiffLineType type;  // 변경 유형
    // 03/13 - int(-1) → std::optional<int> 전환
    // 이유: -1은 "해당 없음"이라는 의미인데 int 타입에서는 유효한 값과 구분 불명확
    //       optional은 값이 없음(nullopt)을 타입 시스템으로 표현
    //       이미 <optional>을 include하고 있으므로 추가 의존성 없음
    std::optional<int> oldLineNumber;  // 원본에서의 줄 번호 (nullopt이면 해당 없음, 즉 ADDED)
    std::optional<int> newLineNumber;  // 수정본에서의 줄 번호 (nullopt이면 해당 없음, 즉 DELETED)
    std::string content;    // 줄 내용
};

// 변경 블록 (hunk)
// GitHub diff에서 "@@ -3, 7 + 3, 8 @@"로 표시되는 하나의 변경 영역에 해당
// 연속된 변경 줄들과 그 전후 컨텍스트(기본 3줄)를 하나의 hunk로 묶는다
struct DiffHunk {
    int oldStart;                   // 원본 시작 줄 번호
    int oldCount;                   // 원본에서 이 hunk가 포함하는 줄 수
    int newStart;                   // 수정본 시작 줄 번호
    int newCount;                   // 수정본에서 이 hunk가 포함하는 줄 수
    std::vector<DiffLine> lines;    // 이 블록에 포함된 모든 줄 (ADDED + DELETED + UNCHANGED)
};

// ==========================================
// 03/18 - Diff 방식 분류
// ==========================================
// computeDiff가 어떤 방식으로 비교했는지를 결과에 포함
// 로그, 감사, UI 표시 등에서 diff 결과를 해석할 때 필요
enum class DiffMethod {
    TEXT_DIRECT,        // 텍스트 파일: 줄 단위 Myers diff 직접 적용
    TEXT_EXTRACTED,     // 문서 바이너리(HWPX, DOCX, PDF): 텍스트 추출 후 Myers diff
    HASH_ONLY           // 순수 바이너리(JPG, MP4 등): SHA-256 해시 비교만
};

// 전체 비교 결과
// 하나의 비교 작업에 대한 모든 결과를 담는 최종 출력 구조체
struct DiffResult {
    bool isBinary = false;                  // 바이너리 파일 여부
    DiffMethod method = DiffMethod::TEXT_DIRECT;  // 03/18 - diff 방식 표시
    std::string oldHash;            // 원본 SHA-256 해시
    std::string newHash;            // 수정본 SHA-256 해시
    int addedLines = 0;                 // 추가된 줄 수 합계
    int deletedLines = 0;               // 삭제된 줄 수 합계
    std::vector<DiffHunk> hunks;    // 변경 블록 목록 (HASH_ONLY면 비어있음)
    std::string unifiedDiff;        // GitHub 스타일 unified diff 전체 문자열
    std::string summary;            // 로그용 요약 문자열
    // 03/18 - 후행 개행 추적
    bool oldTrailingNewline = true;   // 원본 파일이 개행으로 끝나는지
    bool newTrailingNewline = true;   // 수정본 파일이 개행으로 끝나는지
};

// 03/18 - 줄 분할 결과
// splitLines, splitTextLines의 반환형
// 줄 목록과 함께 후행 개행 여부를 추적하여
// "hello\n"과 "hello"를 구분 가능하게 함
struct SplitLinesResult {
    std::vector<std::string> lines;
    bool hasTrailingNewline = true;  // 파일이 개행으로 끝나는지
};

// ==========================================
// 03/18 - 문서 유형 분류
// ==========================================
// MIME 타입 기반으로 파일의 diff 처리 방식을 결정
// TEXT: 줄 단위 diff 가능 (txt, cpp, xml, json 등)
// EXTRACTABLE_BINARY: 바이너리지만 내부에서 텍스트 추출 가능 (hwpx, docx, pdf 등)
// PURE_BINARY: 텍스트 추출 불가, 해시 비교만 가능 (jpg, mp4, exe 등)
enum class DocumentType {
    TEXT,                   // 텍스트 파일
    EXTRACTABLE_BINARY,     // 텍스트 추출 가능한 문서 바이너리
    PURE_BINARY             // 순수 바이너리
};

// ==========================================
// 03/18 - 텍스트 추출 결과
// ==========================================
// "빈 문서"와 "추출 실패"를 구분하기 위한 구조체
// 예: 빈 PDF에서 텍스트가 없는 것(success=true, text="")과
//     PDF 파서 오류(success=false, text="")는 다른 상황
struct ExtractionResult {
    bool success;        // 추출 성공 여부 (true: 성공, false: 실패/미지원)
    std::string text;    // 추출된 텍스트 (실패 시 빈 문자열)
};

// ==========================================
// 03/18 - DocumentTextExtractor: 문서 텍스트 추출기 (인터페이스)
// ==========================================
// 바이너리 문서 포맷에서 사람이 읽을 수 있는 텍스트를 추출하는 서비스
// 
// 설계 의도:
//   DLP에서 민감 정보가 "어떻게 변경되었는지"를 추적하려면,
//   바이너리 문서도 텍스트 수준에서 diff가 가능해야 함.
//   예: DOCX에서 "계약금"이 "선수금"으로 변경된 것을 감지
//
// 각 포맷의 내부 구조:
//   HWPX: ZIP → XML (body.xml 등에 텍스트 포함)
//   DOCX: ZIP → XML (word/document.xml에 텍스트 포함)
//   PDF:  자체 오브젝트 구조 (텍스트 추출에 전용 라이브러리 필요)
//
// 구현 시점:
//   현재는 인터페이스만 정의. Java 전환 시 각 포맷별 구현체를 작성
//   - HWPX/DOCX: java.util.zip + javax.xml.parsers
//   - PDF: Apache PDFBox
class DocumentTextExtractor {
public:
    // 지원하는 MIME 타입인지 확인
    // 반환: true이면 extractText로 텍스트 추출 가능
    bool canExtract(const std::string& mimeType) {
        // 텍스트 추출 가능한 MIME 타입 목록
        // 향후 포맷 추가 시 이 목록만 확장하면 됨
        static const std::vector<std::string> extractable = {
            "application/vnd.hancom.hwpx",                                      // HWPX (한글)
            "application/haansofthwpx",                                         // HWPX (대체 MIME)
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",  // DOCX
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",        // XLSX
            "application/vnd.openxmlformats-officedocument.presentationml.presentation", // PPTX
            "application/pdf",                                                  // PDF
        };
        return std::find(extractable.begin(), extractable.end(), mimeType) != extractable.end();
    }

    // 바이너리 문서에서 텍스트 추출
    // 매개변수: content - 파일 바이너리 데이터 + MIME 타입
    // 반환: ExtractionResult
    //   - success=true, text="..." : 추출 성공 (빈 문서도 success=true, text="" 가능)
    //   - success=false, text=""   : 추출 실패 또는 미지원 → HASH_ONLY fallback
    //
    // 03/18 - 반환형 변경: std::string → ExtractionResult
    //   이유: 빈 문자열만으로는 "빈 문서"와 "추출 실패"를 구분할 수 없음
    //   예: 빈 PDF 두 개를 비교할 때 추출 실패와 동일 취급되는 문제 해결
    //
    // TODO: Java 전환 시 포맷별 구현
    //   HWPX/DOCX: ZIP 해제 → XML 파싱 → 텍스트 노드 추출
    //   PDF: PDFBox의 PDFTextStripper 사용
    ExtractionResult extractText(const FileContent& content) {
        const std::string& mime = content.mimeType;

        if (mime == "application/vnd.hancom.hwpx" ||
            mime == "application/haansofthwpx") {
            return extractFromHWPX(content);
        }
        if (mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document") {
            return extractFromDOCX(content);
        }
        if (mime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") {
            return extractFromXLSX(content);
        }
        if (mime == "application/vnd.openxmlformats-officedocument.presentationml.presentation") {
            return extractFromPPTX(content);
        }
        if (mime == "application/pdf") {
            return extractFromPDF(content);
        }

        return {false, ""};  // 미지원 포맷
    }

private:
    // ── 포맷별 텍스트 추출 (pseudo-code: 구조만 정의) ──
    // Java 전환 시 각 메서드를 실제 구현으로 교체
    // 반환: ExtractionResult {success, text}

    // HWPX: ZIP 내부 Contents/section0.xml 등에서 <hp:t> 태그의 텍스트 추출
    ExtractionResult extractFromHWPX(const FileContent& content) {
        // TODO: ZIP 해제 → XML 파싱 → <hp:t> 텍스트 노드 추출
        // 실패 시 {false, ""} 반환
        return {false, ""};
    }

    // DOCX: ZIP 내부 word/document.xml에서 <w:t> 태그의 텍스트 추출
    ExtractionResult extractFromDOCX(const FileContent& content) {
        // TODO: ZIP 해제 → word/document.xml 파싱 → <w:t> 텍스트 노드 추출
        return {false, ""};
    }

    // XLSX: ZIP 내부 xl/sharedStrings.xml + xl/worksheets/sheet*.xml에서 셀 텍스트 추출
    ExtractionResult extractFromXLSX(const FileContent& content) {
        // TODO: ZIP 해제 → sharedStrings + worksheet 파싱 → 셀 값 추출
        return {false, ""};
    }

    // PPTX: ZIP 내부 ppt/slides/slide*.xml에서 <a:t> 태그의 텍스트 추출
    ExtractionResult extractFromPPTX(const FileContent& content) {
        // TODO: ZIP 해제 → slide XML 파싱 → <a:t> 텍스트 노드 추출
        return {false, ""};
    }

    // PDF: 페이지별 텍스트 스트림 디코딩
    ExtractionResult extractFromPDF(const FileContent& content) {
        // TODO: PDFBox PDFTextStripper.getText(document)
        return {false, ""};
    }
};

// ============================================
// DiffService 클래스
// 02/11 - 구조체 정의
// 03/05 - 전체 구현: Myers diff + SHA-256
// 03/13 - 재통합 + prepareVersionComparison 연동 준비
// 03/18 - FNV-1a 제거 (SHA-256 일원화)
//       - 바이너리 diff 확장: 문서 포맷(HWPX, DOCX, PDF 등)에서 텍스트 추출 후 diff
// ============================================
// 두 파일 버전 간의 차이를 서버 측에서 계산하는 서비스
// GitHub 스타일의 unified diff 형식으로 결과를 제공

// 설계원칙:
// - 텍스트 파일: Myers diff 기반 줄 단위 diff → 최단 편집 스크립트(SES) 제공
//   (Git이 실제 사용하는 알고리즘, O(ND) 시간복잡도 - N: 전체 줄 수, D: 차이 수)
// - 문서 바이너리 (HWPX, DOCX, PDF 등): 텍스트 추출 → Myers diff 적용
//   (03/18 추가: DLP에서 민감 정보 변경 추적을 위해 문서 내용 수준 diff 지원)
// - 순수 바이너리 (JPG, MP4 등): SHA-256 해시 비교 → 변경 여부만 판별
// - 결과는 DiffResult 구조체로 반환, DB 저장 및 로그에 활용
class DiffService {
public:
    // ==========================================
    // computeDiff: 핵심 진입점 - 두 FileContent 간의 diff를 계산
    // ==========================================
    // 매개변수:
    //  oldContent - 이전 버전의 파일 콘텐츠
    //  newContent - 새 버전의 파일 콘텐츠
    // 반환: DiffResult
    //   - TEXT_DIRECT: 텍스트 파일 줄 단위 diff
    //   - TEXT_EXTRACTED: 문서에서 텍스트 추출 후 줄 단위 diff
    //   - HASH_ONLY: 해시 비교만 (순수 바이너리 또는 텍스트 추출 실패)
    //
    // 03/18 확장 흐름:
    //   1. SHA-256 동일성 판별 → 동일하면 즉시 반환
    //   2. 바이너리 체크 (NULL 바이트 탐지)
    //      2-a. 텍스트 파일 → Myers diff (기존)
    //      2-b. 바이너리 → 텍스트 추출 가능? (MIME 타입 기반)
    //           → 가능: 추출 후 Myers diff (TEXT_EXTRACTED)
    //           → 불가 또는 실패: 해시 비교만 (HASH_ONLY)
    DiffResult computeDiff(const FileContent& oldContent, const FileContent& newContent) {
        DiffResult result;

        // 1. SHA-256으로 동일성 판별
        // 03/18 - FNV-1a 제거, SHA-256으로 일원화
        //   이유: pseudo-code 단계에서 실측 없는 조기 최적화(premature optimization)이며,
        //         실제 문서 크기(수KB~수MB)에서 SHA-256과 FNV-1a의 속도 차이는 체감 불가.
        //         해시 함수를 2개 관리하는 복잡도 증가 대비 이점이 없음.
        //         SHA-256 단일 사용으로 코드 단순화 + DLP 무결성 검증과 일관성 확보.
        std::string oldHash = computeSHA256(oldContent.data);
        std::string newHash = computeSHA256(newContent.data);

        if (oldHash == newHash) {
            // 03/18 - 해시 동일 시 isBinary 정확하게 설정
            //   변경 전: 무조건 isBinary=false, method=TEXT_DIRECT
            //   문제: 바이너리 파일이어도 "텍스트로 비교됨"으로 기록됨
            //   변경 후: isBinaryContent 체크하여 정확한 메타데이터 설정
            bool binary = isBinaryContent(oldContent) || isBinaryContent(newContent);
            result.isBinary = binary;
            result.method = binary ? DiffMethod::HASH_ONLY : DiffMethod::TEXT_DIRECT;
            result.addedLines = 0;
            result.deletedLines = 0;
            result.oldHash = oldHash;
            result.newHash = newHash;
            result.summary = "No changes detected";
            return result;
        }

        // 2. 바이너리 파일 체크
        if (isBinaryContent(oldContent) || isBinaryContent(newContent)) {
            result.oldHash = oldHash;
            result.newHash = newHash;

            // 2-b. 텍스트 추출 가능한 문서 바이너리인지 확인 (03/18 추가)
            if (textExtractor.canExtract(oldContent.mimeType) ||
                textExtractor.canExtract(newContent.mimeType)) {

                // 텍스트 추출 시도
                // 03/18 - ExtractionResult로 성공/실패 구분
                //   변경 전: !oldText.empty() || !newText.empty() → 빈 문서와 추출 실패 구분 불가
                //   변경 후: success 플래그로 명확하게 구분
                auto oldResult = textExtractor.extractText(oldContent);
                auto newResult = textExtractor.extractText(newContent);

                // 추출 성공: 두 버전 모두 성공한 경우에만 텍스트 기반 diff 수행
                // 버그 수정: || 조건은 한쪽만 성공해도 TEXT_EXTRACTED로 처리하여
                //   실패한 쪽이 빈 텍스트로 취급되고 "전체 내용이 추가/삭제됨"처럼 보이는 문제 발생
                //   → && 조건으로 변경: 한쪽이라도 실패하면 HASH_ONLY로 fallback
                if (oldResult.success && newResult.success) {
                    result.isBinary = true;
                    result.method = DiffMethod::TEXT_EXTRACTED;

                    auto oldLines = splitTextLines(oldResult.text);
                    auto newLines = splitTextLines(newResult.text);
                    return buildTextDiffResult(result, oldLines, newLines);
                }
                // 한쪽 또는 양쪽 추출 실패: HASH_ONLY로 fallback
                // (부분 추출 성공을 TEXT_EXTRACTED로 처리하면 잘못된 diff 표시 위험)
            }

            // 순수 바이너리 또는 텍스트 추출 실패: 해시 비교만
            result.isBinary = true;
            result.method = DiffMethod::HASH_ONLY;
            result.addedLines = 0;
            result.deletedLines = 0;
            result.summary = "Binary files differ (SHA-256: " +
                            oldHash.substr(0, 8) + "... -> " +
                            newHash.substr(0, 8) + "...)";
            return result;
        }

        // 2-a. 텍스트 파일: 줄 단위 diff (기존 동작)
        result.isBinary = false;
        result.method = DiffMethod::TEXT_DIRECT;
        result.oldHash = oldHash;
        result.newHash = newHash;
        auto oldLines = splitLines(oldContent);
        auto newLines = splitLines(newContent);
        return buildTextDiffResult(result, oldLines, newLines);
    }

private:
    // 03/18 - 문서 텍스트 추출기 (바이너리 diff 확장용)
    DocumentTextExtractor textExtractor;

    // ==========================================
    // splitTextLines: 문자열 → 줄 단위 벡터 + 후행 개행 추적
    // ==========================================
    // 03/18 추가 — extractText 결과(std::string)를 줄 단위로 분할
    // 03/18 수정 — 반환형을 SplitLinesResult로 변경
    //   이유: "hello\n"과 "hello"가 동일하게 {"hello"}를 생성하던 문제 해결
    //         Git의 "\ No newline at end of file" 표시를 위해 후행 개행 여부 추적
    SplitLinesResult splitTextLines(const std::string& text) {
        SplitLinesResult result;
        if (text.empty()) {
            result.hasTrailingNewline = true;  // 빈 파일은 개행 있는 것으로 취급
            return result;
        }
        std::string currentLine;
        for (size_t i = 0; i < text.size(); i++) {
            if (text[i] == '\r') {
                result.lines.push_back(currentLine);
                currentLine.clear();
                if (i + 1 < text.size() && text[i + 1] == '\n') i++;
            } else if (text[i] == '\n') {
                result.lines.push_back(currentLine);
                currentLine.clear();
            } else {
                currentLine += text[i];
            }
        }
        // 마지막 줄 처리: 개행 없이 끝나는 경우
        if (!currentLine.empty()) {
            result.lines.push_back(currentLine);
            result.hasTrailingNewline = false;  // 개행 없이 끝남
        } else {
            result.hasTrailingNewline = true;   // 개행으로 끝남
        }
        return result;
    }

    // ==========================================
    // buildTextDiffResult: 줄 벡터로부터 DiffResult 완성
    // ==========================================
    // 03/18 추가 — TEXT_DIRECT와 TEXT_EXTRACTED 경로의 공통 로직 추출
    // 03/18 수정 — SplitLinesResult로 후행 개행 정보까지 전달
    // result에 이미 isBinary, method, oldHash, newHash가 설정된 상태로 호출
    DiffResult buildTextDiffResult(DiffResult result,
                                    const SplitLinesResult& oldSplit,
                                    const SplitLinesResult& newSplit) {
        auto diffLines = myersDiff(oldSplit.lines, newSplit.lines);

        result.addedLines = 0;
        result.deletedLines = 0;
        for (const auto& line : diffLines) {
            if (line.type == DiffLineType::ADDED) result.addedLines++;
            else if (line.type == DiffLineType::DELETED) result.deletedLines++;
        }

        result.oldTrailingNewline = oldSplit.hasTrailingNewline;
        result.newTrailingNewline = newSplit.hasTrailingNewline;
        result.hunks = groupIntoHunks(diffLines);
        result.unifiedDiff = formatUnifiedDiff(result.hunks,
                                                oldSplit.hasTrailingNewline,
                                                newSplit.hasTrailingNewline,
                                                oldSplit.lines.size(),
                                                newSplit.lines.size());
        result.summary = generateSummary(result);

        return result;
    }

    // ==========================================
    // isBinaryContent: 바이너리 파일 판별
    // ==========================================
    // Git과 동일한 방식: 앞 8KB에서 NULL 바이트(0x00) 탐지
    // NULL이 하나라도 있으면 바이너리로 판단
    bool isBinaryContent(const FileContent& content) {
        size_t checkSize = std::min(content.data.size(), static_cast<size_t>(8192));
        for (size_t i = 0; i < checkSize; i++) {
            if (content.data[i] == 0x00) {
                return true;
            }
        }
        return false;
    }

    // ==========================================
    // splitLines: FileContent → 줄 단위 문자열 벡터 + 후행 개행 추적
    // ==========================================
    // \n, \r\n, \r 모두 처리
    // 03/18 수정 — 반환형을 SplitLinesResult로 변경 (후행 개행 추적)
    SplitLinesResult splitLines(const FileContent& content) {
        SplitLinesResult result;
        std::string text(content.data.begin(), content.data.end());

        if (text.empty()) {
            result.hasTrailingNewline = true;
            return result;
        }

        std::string currentLine;
        for (size_t i = 0; i < text.size(); i++) {
            if (text[i] == '\r') {
                result.lines.push_back(currentLine);
                currentLine.clear();
                // \r\n 처리: \n을 건너뜀
                if (i + 1 < text.size() && text[i + 1] == '\n') {
                    i++;
                }
            } else if (text[i] == '\n') {
                result.lines.push_back(currentLine);
                currentLine.clear();
            } else {
                currentLine += text[i];
            }
        }
        // 마지막 줄 처리
        if (!currentLine.empty()) {
            result.lines.push_back(currentLine);
            result.hasTrailingNewline = false;  // 개행 없이 끝남
        } else {
            result.hasTrailingNewline = true;   // 개행으로 끝남
        }
        return result;
    }

    // ==========================================
    // myersDiff: Myers diff 알고리즘 (핵심)
    // ==========================================
    // Eugene W. Myers, 1986, Algorithmica
    // 편집 그래프에서 (0,0) → (N,M) 최단 경로 탐색
    // x축 이동 = DELETE, y축 이동 = INSERT, 대각선 = EQUAL
    //
    // 시간복잡도: O(ND) - N: 전체 줄 수, D: 차이 수
    // 공간복잡도: O(D * (N+M)) - traces 배열 저장

    // 내부 편집 연산 타입
    enum class EditOp { INSERT, DELETE, EQUAL };
    struct EditEntry {
        EditOp op;
        int oldIdx;     // 원본에서의 인덱스 (-1이면 해당 없음)
        int newIdx;     // 수정본에서의 인덱스 (-1이면 해당 없음)
    };

    std::vector<DiffLine> myersDiff(const std::vector<std::string>& oldLines,
                                     const std::vector<std::string>& newLines) {
        int N = static_cast<int>(oldLines.size());
        int M = static_cast<int>(newLines.size());
        int maxD = N + M;

        // 빈 파일 처리
        if (N == 0 && M == 0) return {};
        if (N == 0) {
            std::vector<DiffLine> result;
            for (int j = 0; j < M; j++) {
                result.push_back({DiffLineType::ADDED, std::nullopt, j + 1, newLines[j]});
            }
            return result;
        }
        if (M == 0) {
            std::vector<DiffLine> result;
            for (int i = 0; i < N; i++) {
                result.push_back({DiffLineType::DELETED, i + 1, std::nullopt, oldLines[i]});
            }
            return result;
        }

        // ── Myers 전진 탐색 (Forward pass) ──
        // V[k]: 대각선 k에서 도달 가능한 최대 x좌표
        // traces: 각 d 단계의 V 상태를 저장 (역추적용)
        int offset = maxD;
        int vSize = 2 * maxD + 1;
        std::vector<int> V(vSize, -1);
        V[offset + 1] = 0;  // 초기 상태: k=1, x=0

        std::vector<std::vector<int>> traces;

        int finalD = -1;
        for (int d = 0; d <= maxD; d++) {
            traces.push_back(V);

            for (int k = -d; k <= d; k += 2) {
                // 이동 방향 결정:
                // k == -d → 아래(INSERT)만 가능
                // k == d → 오른쪽(DELETE)만 가능
                // 그 외: V[k-1]과 V[k+1] 비교하여 더 멀리 간 쪽 선택
                int x;
                if (k == -d || (k != d && V[offset + k - 1] < V[offset + k + 1])) {
                    x = V[offset + k + 1];      // INSERT (k+1에서 내려옴)
                } else {
                    x = V[offset + k - 1] + 1;  // DELETE (k-1에서 옆으로)
                }
                int y = x - k;

                // Snake: 동일한 줄이면 대각선 따라감
                while (x < N && y < M && oldLines[x] == newLines[y]) {
                    x++;
                    y++;
                }

                V[offset + k] = x;

                // 목표 (N, M) 도달 확인
                if (x >= N && y >= M) {
                    finalD = d;
                    break;
                }
            }
            if (finalD >= 0) break;
        }

        // ── 역추적 (Backtracking) ──
        // traces를 역순으로 따라가며 실제 편집 연산 복원
        // 주의: traces[d]는 d단계 시작 시점의 V 스냅샷 = d-1 완료 후 상태
        //       따라서 d단계의 편집을 역추적할 때 traces[d]를 참조해야 함
        std::vector<EditEntry> edits;
        int x = N, y = M;

        for (int d = finalD; d > 0; d--) {
            const auto& prevV = traces[d];  // 03/13 수정: d-1 → d (d단계 시작 시점 = d-1 완료 후)
            int k = x - y;

            int prevK;
            if (k == -d || (k != d && prevV[offset + k - 1] < prevV[offset + k + 1])) {
                prevK = k + 1;  // 이전 단계에서 INSERT로 도달
            } else {
                prevK = k - 1;  // 이전 단계에서 DELETE로 도달
            }

            int prevX = prevV[offset + prevK];
            int prevY = prevX - prevK;

            // Snake 구간 (대각선 = EQUAL)
            while (x > prevX && y > prevY) {
                x--; y--;
                edits.push_back({EditOp::EQUAL, x, y});
            }

            // 실제 편집 연산
            if (x == prevX && y > prevY) {
                y--;
                edits.push_back({EditOp::INSERT, -1, y});
            } else if (y == prevY && x > prevX) {
                x--;
                edits.push_back({EditOp::DELETE, x, -1});
            }
        }

        // 남은 Snake (d=0에서의 초기 대각선)
        while (x > 0 && y > 0) {
            x--; y--;
            edits.push_back({EditOp::EQUAL, x, y});
        }

        // edits는 역순이므로 뒤집기
        std::reverse(edits.begin(), edits.end());

        // EditEntry → DiffLine 변환
        std::vector<DiffLine> result;
        for (const auto& edit : edits) {
            DiffLine line;
            switch (edit.op) {
                case EditOp::EQUAL:
                    line.type = DiffLineType::UNCHANGED;
                    line.oldLineNumber = edit.oldIdx + 1;
                    line.newLineNumber = edit.newIdx + 1;
                    line.content = oldLines[edit.oldIdx];
                    break;
                case EditOp::DELETE:
                    line.type = DiffLineType::DELETED;
                    line.oldLineNumber = edit.oldIdx + 1;
                    line.newLineNumber = std::nullopt;
                    line.content = oldLines[edit.oldIdx];
                    break;
                case EditOp::INSERT:
                    line.type = DiffLineType::ADDED;
                    line.oldLineNumber = std::nullopt;
                    line.newLineNumber = edit.newIdx + 1;
                    line.content = newLines[edit.newIdx];
                    break;
            }
            result.push_back(line);
        }
        return result;
    }

    // ==========================================
    // groupIntoHunks: 연속 변경을 hunk로 그룹핑
    // ==========================================
    // GitHub 스타일: 변경 전후 컨텍스트 3줄, 겹치면 병합
    std::vector<DiffHunk> groupIntoHunks(const std::vector<DiffLine>& diffLines,
                                          int contextLines = 3) {
        std::vector<DiffHunk> hunks;
        if (diffLines.empty()) return hunks;

        // 변경된 줄의 인덱스 수집
        std::vector<int> changeIndices;
        for (int i = 0; i < static_cast<int>(diffLines.size()); i++) {
            if (diffLines[i].type != DiffLineType::UNCHANGED) {
                changeIndices.push_back(i);
            }
        }
        if (changeIndices.empty()) return hunks;

        // 변경 영역을 컨텍스트 포함하여 그룹핑
        int totalLines = static_cast<int>(diffLines.size());
        int groupStart = std::max(0, changeIndices[0] - contextLines);
        int groupEnd = std::min(totalLines - 1, changeIndices[0] + contextLines);

        std::vector<std::pair<int, int>> groups;

        for (size_t i = 1; i < changeIndices.size(); i++) {
            int newStart = std::max(0, changeIndices[i] - contextLines);
            int newEnd = std::min(totalLines - 1, changeIndices[i] + contextLines);

            if (newStart <= groupEnd + 1) {
                // 겹침 → 병합
                groupEnd = newEnd;
            } else {
                // 분리 → 이전 그룹 저장, 새 그룹 시작
                groups.push_back({groupStart, groupEnd});
                groupStart = newStart;
                groupEnd = newEnd;
            }
        }
        groups.push_back({groupStart, groupEnd});

        // 각 그룹을 DiffHunk로 변환
        for (const auto& [start, end] : groups) {
            DiffHunk hunk;
            hunk.oldStart = 0;
            hunk.oldCount = 0;
            hunk.newStart = 0;
            hunk.newCount = 0;

            bool firstOld = true, firstNew = true;

            for (int i = start; i <= end; i++) {
                const auto& line = diffLines[i];
                hunk.lines.push_back(line);

                if (line.type == DiffLineType::UNCHANGED || line.type == DiffLineType::DELETED) {
                    if (firstOld && line.oldLineNumber.has_value()) {
                        hunk.oldStart = line.oldLineNumber.value();
                        firstOld = false;
                    }
                    hunk.oldCount++;
                }
                if (line.type == DiffLineType::UNCHANGED || line.type == DiffLineType::ADDED) {
                    if (firstNew && line.newLineNumber.has_value()) {
                        hunk.newStart = line.newLineNumber.value();
                        firstNew = false;
                    }
                    hunk.newCount++;
                }
            }
            hunks.push_back(hunk);
        }
        return hunks;
    }

    // ==========================================
    // formatUnifiedDiff: GitHub 스타일 unified diff 출력
    // ==========================================
    // 형식: @@ -oldStart,oldCount +newStart,newCount @@
    // 03/18 수정 — 후행 개행 미존재 시 "\ No newline at end of file" 표시
    //   Git과 동일한 방식: 원본/수정본의 마지막 줄 뒤에 개행이 없으면 마커 추가
    std::string formatUnifiedDiff(const std::vector<DiffHunk>& hunks,
                                   bool oldTrailingNewline = true,
                                   bool newTrailingNewline = true,
                                   size_t oldTotalLines = 0,
                                   size_t newTotalLines = 0) {
        std::ostringstream oss;
        for (const auto& hunk : hunks) {
            oss << "@@ -" << hunk.oldStart << "," << hunk.oldCount
                << " +" << hunk.newStart << "," << hunk.newCount << " @@\n";

            for (const auto& line : hunk.lines) {
                switch (line.type) {
                    case DiffLineType::ADDED:
                        oss << "+" << line.content << "\n";
                        // 수정본의 마지막 줄이고 후행 개행이 없으면 마커 추가
                        if (!newTrailingNewline && line.newLineNumber.has_value()
                            && line.newLineNumber.value() == static_cast<int>(newTotalLines)) {
                            oss << "\\ No newline at end of file\n";
                        }
                        break;
                    case DiffLineType::DELETED:
                        oss << "-" << line.content << "\n";
                        // 원본의 마지막 줄이고 후행 개행이 없으면 마커 추가
                        if (!oldTrailingNewline && line.oldLineNumber.has_value()
                            && line.oldLineNumber.value() == static_cast<int>(oldTotalLines)) {
                            oss << "\\ No newline at end of file\n";
                        }
                        break;
                    case DiffLineType::UNCHANGED:
                        oss << " " << line.content << "\n";
                        // UNCHANGED는 양쪽 모두 참조 — 각각 확인
                        if (!oldTrailingNewline && line.oldLineNumber.has_value()
                            && line.oldLineNumber.value() == static_cast<int>(oldTotalLines)) {
                            oss << "\\ No newline at end of file\n";
                        }
                        if (!newTrailingNewline && line.newLineNumber.has_value()
                            && line.newLineNumber.value() == static_cast<int>(newTotalLines)
                            && oldTrailingNewline) {  // old에서 이미 표시했으면 중복 방지
                            oss << "\\ No newline at end of file\n";
                        }
                        break;
                }
            }
        }
        return oss.str();
    }

    // ==========================================
    // generateSummary: 로그용 요약 문자열 생성
    // ==========================================
    // 03/18 - TEXT_EXTRACTED 방식 지원 추가
    std::string generateSummary(const DiffResult& result) {
        // HASH_ONLY: 바이너리 해시 비교 (summary가 이미 설정됨)
        if (result.method == DiffMethod::HASH_ONLY) {
            return result.summary;
        }
        // TEXT_DIRECT / TEXT_EXTRACTED: 줄 단위 diff 결과 요약
        std::string prefix = (result.method == DiffMethod::TEXT_EXTRACTED)
                           ? "[extracted] " : "";
        return prefix +
               std::to_string(result.addedLines) + " addition(s), " +
               std::to_string(result.deletedLines) + " deletion(s), " +
               std::to_string(result.hunks.size()) + " hunk(s)";
    }

    // ==========================================
    // computeSHA256: SHA-256 해시 (암호학적 해시)
    // ==========================================
    // FIPS 180-4 참조 구현, 외부 의존성 없음
    // 03/18 - 역할 변경: SHA-256으로 일원화
    //   이전 (03/13): 바이너리 파일 해시 표시 전용 (텍스트는 FNV-1a)
    //   현재: 동일성 판별 + 바이너리 해시 + 텍스트 해시 (전체 통합)
    //   향후: DLP 모듈의 파일 무결성 검증용으로도 활용 예정
    std::string computeSHA256(const std::vector<uint8_t>& data) {
        // SHA-256 상수: 처음 64개 소수의 세제곱근의 소수 부분
        static const uint32_t K[64] = {
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5,
            0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
            0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
            0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
            0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
            0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
            0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
            0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
            0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
            0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
        };

        // 비트 연산 람다
        auto rotr = [](uint32_t x, int n) -> uint32_t {
            return (x >> n) | (x << (32 - n));
        };
        auto ch = [](uint32_t x, uint32_t y, uint32_t z) -> uint32_t {
            return (x & y) ^ (~x & z);
        };
        auto maj = [](uint32_t x, uint32_t y, uint32_t z) -> uint32_t {
            return (x & y) ^ (x & z) ^ (y & z);
        };
        auto sigma0 = [&rotr](uint32_t x) -> uint32_t {
            return rotr(x, 2) ^ rotr(x, 13) ^ rotr(x, 22);
        };
        auto sigma1 = [&rotr](uint32_t x) -> uint32_t {
            return rotr(x, 6) ^ rotr(x, 11) ^ rotr(x, 25);
        };
        auto gamma0 = [&rotr](uint32_t x) -> uint32_t {
            return rotr(x, 7) ^ rotr(x, 18) ^ (x >> 3);
        };
        auto gamma1 = [&rotr](uint32_t x) -> uint32_t {
            return rotr(x, 17) ^ rotr(x, 19) ^ (x >> 10);
        };

        // 초기 해시 값: 처음 8개 소수의 제곱근의 소수 부분
        uint32_t H[8] = {
            0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
            0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
        };

        // 메시지 패딩
        std::vector<uint8_t> msg(data);
        uint64_t bitLen = static_cast<uint64_t>(data.size()) * 8;
        msg.push_back(0x80);
        while (msg.size() % 64 != 56) {
            msg.push_back(0x00);
        }
        for (int i = 7; i >= 0; i--) {
            msg.push_back(static_cast<uint8_t>((bitLen >> (i * 8)) & 0xFF));
        }

        // 64바이트 블록 단위 처리
        for (size_t offset = 0; offset < msg.size(); offset += 64) {
            uint32_t W[64];

            // 메시지 스케줄
            for (int t = 0; t < 16; t++) {
                W[t] = (static_cast<uint32_t>(msg[offset + t * 4]) << 24) |
                       (static_cast<uint32_t>(msg[offset + t * 4 + 1]) << 16) |
                       (static_cast<uint32_t>(msg[offset + t * 4 + 2]) << 8) |
                       (static_cast<uint32_t>(msg[offset + t * 4 + 3]));
            }
            for (int t = 16; t < 64; t++) {
                W[t] = gamma1(W[t - 2]) + W[t - 7] + gamma0(W[t - 15]) + W[t - 16];
            }

            // 압축 함수
            uint32_t a = H[0], b = H[1], c = H[2], d = H[3];
            uint32_t e = H[4], f = H[5], g = H[6], h = H[7];

            for (int t = 0; t < 64; t++) {
                uint32_t T1 = h + sigma1(e) + ch(e, f, g) + K[t] + W[t];
                uint32_t T2 = sigma0(a) + maj(a, b, c);
                h = g; g = f; f = e;
                e = d + T1;
                d = c; c = b; b = a;
                a = T1 + T2;
            }

            H[0] += a; H[1] += b; H[2] += c; H[3] += d;
            H[4] += e; H[5] += f; H[6] += g; H[7] += h;
        }

        // 해시 결과를 16진수 문자열로 변환
        std::ostringstream oss;
        for (int i = 0; i < 8; i++) {
            oss << std::hex << std::setfill('0') << std::setw(8) << H[i];
        }
        return oss.str();
    }
};