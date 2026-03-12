// c++17
// DocumentVersionWorkflowAPI: Nextcloud 기반 문서 버전 관리 및 워크플로우 API
// 위 클래스는 서버측 애플리케이션 서비스 계층이고, 
// 클라이언트(Desktop/Web/Mobile)에서 호출 가능한 API 제공 목적.

#include <string>
#include <vector>
#include <map>
#include <chrono>
#include <algorithm> // std::find를 위해 필요
#include <iostream>  // 기본적인 입출력을 위해 필요
#include <optional>  // std::optional 사용을 위해 필요
#include <cstdint>   // int64_t, unit8_t 사용을 위해
#include <stdexcept> // 예외 처리용
#include <sstream>   // std::istringstream, std::ostringstream 사용을 위해
#include <numeric>   // std::accumulate 사용을 위해 (해시 등)
#include <functional> // std::hash 사용을 위해
#include <unordered_set> // 03/05 - 삭제 대상 중복 체크 O(1)을 위해
#include <iomanip>       // 03/13 - std::setfill, std::setw (SHA-256 hex 출력용)

// 데이터 구조체 정의

// 파일 콘텐츠 및 메타데이터
struct FileContent {
    std::vector<uint8_t> data;
    std::string mimeType;
    // 03/13 - size 멤버 변수를 메서드로 변환
    // 이유: data를 resize한 뒤 size를 갱신하지 않으면 DB에 잘못된 크기가 기록됨
    //       size() 메서드는 항상 data.size()를 반환하므로 불일치 불가
    // 변경 영향: content.size → content.size() (6곳)
    size_t size() const { return data.size(); }
};

// 버전 레코드 정보
struct VersionInfo {
    std::string versionId;      // 버전 고유 ID (형식: {fileId}.v{timestamp} timestamp 기반 고유 ID
    std::string fileId;         // 파일 고유 ID
    std::string userId;          // 버전 생성자
    int64_t timestamp;           // 생성 시각 (Unix timestamp)
    size_t size;                // 파일 크기 (bytes)
    std::string mimeType;       // MIME 타입
    std::map<std::string, std::string> metadata;  // 추가 메타데이터
    // TODO: DLP 연동 시 metadata에 다음 키들이 추가될 예정으로 추측
    // - "author": 원작성자
    // - "dlp.hasSensitiveData": 민감정보 포함 여부
    // - "dlp.classification": 보안 등급
};

// 활동 로그 기록
struct ActivityEntry {
    std::string userId; // 활동 수행자
    std::string action; // 활동 유형
    int64_t timestamp;  // 활동 시각
    std::string subject;    // 활동 제목
    std::string message;    // 활동 상세 메시지
    std::string objectType;
    std::string objectId;   // 대상 객체 ID
    std::optional<std::string> reason;  // 변경 이유 (커스텀 필드)
};

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

// 전체 비교 결과
// 하나의 비교 작업에 대한 모든 결과를 담는 최종 출력 구조체
struct DiffResult {
    bool isBinary = false;                  // 바이너리 파일 여부 (true면 해시 비교만 수행)
    std::string oldHash;            // 원본 SHA-256 해시 (간이 해시)
    std::string newHash;            // 수정본 SHA-256 해시 (간이 해시)
    int addedLines = 0;                 // 추가된 줄 수 합계
    int deletedLines = 0;               // 삭제된 줄 수 합계
    std::vector<DiffHunk> hunks;    // 변경 블록 목록 (바이너리면 비어있음)
    std::string unifiedDiff;        // GitHub 스타일 unified diff 전체 문자열
    std::string summary;            // 로그용 요약 문자열
};

// 버전 비교용 콘텐츠 쌍
// 02/11 - DiffResult 필드 추가: 서버 측에서 계산된 diff 결과를 포함
struct DiffInfo {
    std::string versionId1; // 비교 대상 버전 1
    std::string versionId2; // 비교 대상 버전 2
    FileContent content1;   // 버전 1의 파일 콘텐츠
    FileContent content2;   // 버전 2의 파일 콘텐츠
    DiffResult diffResult;  // 서버 측 diff 계산 결과
};

// 문서 상태
enum class DocumentStatus {
    DRAFT,          // 초안
    UNDER_REVIEW,   // 검토중
    APPROVED,       // 승인됨
    REJECTED,       // 거절됨
    DEPRECATED      // 폐기됨
};

// 승인 워크플로우 액션
enum class ApprovalAction {
    REQUEST,        // 승인 요청
    APPROVE,        // 승인
    REJECT          // 승인 거절
};

// 알림 수신자 정보
struct NotificationTarget {
    std::string userId;
    std::vector<std::string> channels;  // 알림 채널 목록 ("push", "email", "web")
};

/* claude가 추천하는 열거형 구조체 / 타입 안정성
enum class NotificationChannel {
    PUSH,
    EMAIL,
    WEB
};

struct NotificationTarget {
    std::string userId;
    std::vector<NotificationChannel> channels;
}

*/

// 버전 보존 정책 설정
struct RetentionPolicy {
    int minDays = 0;           // 최소 보관 일수 (일) - 이 기간 내 버전은 무조건 보존
    int maxDays = 0;           // 최대 보관 일수 (일) - 이 기간 초과 버전은 삭제 (0 = 무제한)
    bool autoCleanup = true;   // 공간 부족 시 자동 정리 활성화 여부
    int maxVersions = 0;       // 최대 버전 수 (0 = 무제한)
};

// 가상의 의존성 클래스들 (Forward Declaration)
// 03/13 - 전체 의존성 클래스 파라미터를 const& 전환
// 이유: FileContent(vector<uint8_t> 포함)는 수MB~수GB 가능, 매번 복사는 성능 문제
//       string, vector, map도 값 복사 불필요 (읽기 전용)
// 역할 미정의
class VersionService {};

// Activity 및 Admin Audit 기록
class AuditLogService { 
public: 
    void logActivity(const std::string& u, const std::string& f,
                     const std::string& a, const std::string& m) {} 
};

// 역할 미정의
class DocumentStatusManager {};

// 이벤트 디스패치 및 룰 평가
class WorkflowEngine { 
public: 
    void dispatchEvent(const std::string& e, const std::map<std::string, std::string>& d) {} 
    void evaluateRules(const std::string& e, const std::map<std::string, std::string>& d) {} 
};

// 푸시, 이메일, 웹 알림 발송
class NotificationService { 
public: 
    void notifyFileSubscribers(const std::string& f, const std::string& m, const std::string& u) {} 
    void sendNotification(const std::string& u, const std::string& s,
                          const std::string& m, const std::vector<std::string>& c) {} 
};

// 버전 보존 정책 적용
class PolicyManager { 
public: 
    void applyRetentionPolicy(const std::string& f) {} 
};

// 파일 읽기, 쓰기, 복사, 삭제
class FileStorage {
public: 
    std::string generateFileId(const std::string& p) { return "file_id_" + p; }
    void writeFile(const std::string& id, const FileContent& c) {}
    void copyFile(const std::string& src, const std::string& dst) {}
    FileContent readFile(const std::string& id) { return FileContent(); }
    void deleteFile(const std::string& path) {}
};

// DB 쿼리 실행 및 결과 조회
class DatabaseConnection {
public:
    // 편의상 모든 인자를 문자열로 변환하여 받는다고 가정
    void execute(const std::string& query, const std::vector<std::string>& params) {}
    std::vector<std::map<std::string, std::string>> query(const std::string& q,
                                                           const std::vector<std::string>& params) { return {}; }
};

// ============================================
// DiffService 클래스
// 02/11 - 구조체 정의
// 03/05 - 전체 구현: Myers diff + SHA-256
// 03/13 - 재통합 + prepareVersionComparison 연동 준비
// ============================================
// 두 파일 버전 간의 차이를 서버 측에서 계산하는 서비스
// GitHub 스타일의 unified diff 형식으로 결과를 제공

// 설계원칙:
// - 텍스트 파일: Myers diff 기반 줄 단위 diff → 최단 편집 스크립트(SES) 제공
//   (Git이 실제 사용하는 알고리즘, O(ND) 시간복잡도 - N: 전체 줄 수, D: 차이 수)
// - 바이너리 파일: SHA-256 해시 비교 → 변경 여부만 판별
// - 결과는 DiffResult 구조체로 반환, DB 저장 및 로그에 활용
class DiffService {
public:
    // ==========================================
    // computeDiff: 핵심 진입점 - 두 FileContent 간의 diff를 계산
    // ==========================================
    // 매개변수:
    //  oldContent - 이전 버전의 파일 콘텐츠
    //  newContent - 새 버전의 파일 콘텐츠
    // 반환: DiffResult (바이너리면 해시만, 텍스트면 상세 diff 포함)
    DiffResult computeDiff(const FileContent& oldContent, const FileContent& newContent) {
        DiffResult result;

        // 1. FNV-1a로 빠른 동일성 판별 (03/13 최적화)
        // SHA-256 대비 수십 배 빠름 — 동일 파일이면 여기서 즉시 반환
        std::string oldFnv = computeFNV1a(oldContent.data);
        std::string newFnv = computeFNV1a(newContent.data);

        if (oldFnv == newFnv) {
            result.isBinary = false;
            result.addedLines = 0;
            result.deletedLines = 0;
            result.oldHash = oldFnv;
            result.newHash = newFnv;
            result.summary = "No changes detected";
            return result;
        }

        // 2. 바이너리 파일 체크
        if (isBinaryContent(oldContent) || isBinaryContent(newContent)) {
            result.isBinary = true;
            result.addedLines = 0;
            result.deletedLines = 0;
            // 바이너리 파일은 SHA-256으로 의미 있는 해시 표시
            // (DLP 무결성 검증과도 호환되는 암호학적 해시)
            result.oldHash = computeSHA256(oldContent.data);
            result.newHash = computeSHA256(newContent.data);
            result.summary = "Binary files differ (SHA-256: " +
                            result.oldHash.substr(0, 8) + "... -> " +
                            result.newHash.substr(0, 8) + "...)";
            return result;
        }

        // 3. 텍스트 diff 계산 — FNV-1a 해시 사용 (충분)
        result.isBinary = false;
        result.oldHash = oldFnv;
        result.newHash = newFnv;
        auto oldLines = splitLines(oldContent);
        auto newLines = splitLines(newContent);

        // 4. Myers diff 실행
        auto diffLines = myersDiff(oldLines, newLines);

        // 5. 통계 집계
        result.addedLines = 0;
        result.deletedLines = 0;
        for (const auto& line : diffLines) {
            if (line.type == DiffLineType::ADDED) result.addedLines++;
            else if (line.type == DiffLineType::DELETED) result.deletedLines++;
        }

        // 6. Hunk 그룹핑 + 포맷팅
        result.hunks = groupIntoHunks(diffLines);
        result.unifiedDiff = formatUnifiedDiff(result.hunks);
        result.summary = generateSummary(result);

        return result;
    }

private:
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
    // splitLines: FileContent → 줄 단위 문자열 벡터
    // ==========================================
    // \n, \r\n, \r 모두 처리
    std::vector<std::string> splitLines(const FileContent& content) {
        std::vector<std::string> lines;
        std::string text(content.data.begin(), content.data.end());

        std::string currentLine;
        for (size_t i = 0; i < text.size(); i++) {
            if (text[i] == '\r') {
                lines.push_back(currentLine);
                currentLine.clear();
                // \r\n 처리: \n을 건너뜀
                if (i + 1 < text.size() && text[i + 1] == '\n') {
                    i++;
                }
            } else if (text[i] == '\n') {
                lines.push_back(currentLine);
                currentLine.clear();
            } else {
                currentLine += text[i];
            }
        }
        // 마지막 줄 (개행 없이 끝나는 경우)
        if (!currentLine.empty()) {
            lines.push_back(currentLine);
        }
        return lines;
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
    std::string formatUnifiedDiff(const std::vector<DiffHunk>& hunks) {
        std::ostringstream oss;
        for (const auto& hunk : hunks) {
            oss << "@@ -" << hunk.oldStart << "," << hunk.oldCount
                << " +" << hunk.newStart << "," << hunk.newCount << " @@\n";

            for (const auto& line : hunk.lines) {
                switch (line.type) {
                    case DiffLineType::ADDED:
                        oss << "+" << line.content << "\n";
                        break;
                    case DiffLineType::DELETED:
                        oss << "-" << line.content << "\n";
                        break;
                    case DiffLineType::UNCHANGED:
                        oss << " " << line.content << "\n";
                        break;
                }
            }
        }
        return oss.str();
    }

    // ==========================================
    // generateSummary: 로그용 요약 문자열 생성
    // ==========================================
    std::string generateSummary(const DiffResult& result) {
        if (result.isBinary) {
            return result.summary;  // 바이너리는 이미 설정됨
        }
        return std::to_string(result.addedLines) + " addition(s), " +
               std::to_string(result.deletedLines) + " deletion(s), " +
               std::to_string(result.hunks.size()) + " hunk(s)";
    }

    // ==========================================
    // computeFNV1a: FNV-1a 해시 (고속 비암호학적 해시)
    // ==========================================
    // 03/13 추가 — diff 동일성 비교 전용
    // 용도: 두 파일이 동일한지 빠르게 판별 (early return 최적화)
    // 특성: O(n) 시간, 상수 공간, SHA-256 대비 수십 배 빠름
    // 충돌 확률: 64비트 해시이므로 실용적으로 무시 가능 (diff 비교 목적)
    // 주의: 암호학적 용도(무결성 검증, DLP)에는 부적합 → SHA-256 사용
    //
    // FNV-1a 알고리즘: Fowler–Noll–Vo, 1991
    // 공개 도메인(public domain) 알고리즘, 라이선스 제약 없음
    std::string computeFNV1a(const std::vector<uint8_t>& data) {
        // FNV-1a 64비트 초기값과 소수
        uint64_t hash = 0xcbf29ce484222325ULL;   // FNV offset basis
        constexpr uint64_t prime = 0x100000001b3ULL;  // FNV prime

        for (uint8_t byte : data) {
            hash ^= static_cast<uint64_t>(byte);
            hash *= prime;
        }

        // 16진수 문자열로 변환 (16자리)
        std::ostringstream oss;
        oss << std::hex << std::setfill('0') << std::setw(16) << hash;
        return oss.str();
    }

    // ==========================================
    // computeSHA256: SHA-256 해시 (암호학적 해시)
    // ==========================================
    // FIPS 180-4 참조 구현, 외부 의존성 없음
    // 03/13 - 역할 변경:
    //   이전: diff 동일성 판별 + 바이너리 비교 (모든 호출에서 사용)
    //   현재: 바이너리 파일 해시 표시 전용 (텍스트 diff에서는 FNV-1a 사용)
    //   향후: DLP 모듈의 파일 무결성 검증용으로 분리 활용 예정
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

class DocumentVersionWorkflowAPI {
private:
    // 내부 서비스 컴포넌트 (Nextcloud 모듈에 대응)
    // 실제 구현에서는 생성자 주입(Constructor Injection)이 필요함
    VersionService* versionService = nullptr;
    AuditLogService* auditLog = nullptr;
    DocumentStatusManager* statusManager = nullptr;
    WorkflowEngine* workflowEngine = nullptr;
    NotificationService* notificationService = nullptr;
    PolicyManager* policyManager = nullptr;
    FileStorage* fileStorage = nullptr;
    DatabaseConnection* db = nullptr;
    DiffService* diffService = nullptr;  // 03/13 - diff 서비스 연동

    // 03/05 - 태그 이름 상수 정의 (하드코딩 방지)
    // setDocumentStatus, processApprovalWorkflow 등에서 공통 사용
    // 태그 이름 변경 시 이곳만 수정하면 전체 반영
    static constexpr const char* TAG_DRAFT = "draft";
    static constexpr const char* TAG_UNDER_REVIEW = "under_review";
    static constexpr const char* TAG_APPROVED = "approved";
    static constexpr const char* TAG_REJECTED = "rejected";
    static constexpr const char* TAG_DEPRECATED = "deprecated";

public:
    // RD-SRS-9.1: 모든 문서는 고유한 버전 번호를 가져야 함
    // code: apps/files_versions/lib/Storage.php (store),
    //       apps/files_versions/lib/Versions/IVersion.php (getRevisionId)
    //       apps/files_versions/lib/Db/VersionEntity.php
    // 클라이언트에서 호출되는 부분: WebDAV PUT /remote.php/dav/files/{user}/{path}
    VersionInfo createInitialVersion(const std::string& userId,
                                    const std::string& filePath,
                                    const FileContent& content) {
        // 1. 파일 ID 생성 또는 조회
        auto fileId = fileStorage->generateFileId(filePath);

        // 2. 파일 콘텐츠 저장 (maps to OC\Files\Node\File::putContent)
        fileStorage->writeFile(fileId, content);

        // 3. 타임스탬프 기반 고유 버전 ID 생성 (maps to Storage::getVersionNameForFile)
        // 02/10 - timestamp 문제: 초 단위로 통일 (원본은 system_clock::count()로 나노초 반환)
        // auto timestamp = std::chrono::system_clock::now().time_since_epoch().count();
        auto timestamp = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();
        auto versionId = fileId + ".v" + std::to_string(timestamp);

        // 4. 버전 스냅샷 생성 (maps to OCA\Files_Versions\Storage::store)
        std::string versionPath = "files_versions/" + versionId;
        fileStorage->copyFile(fileId, versionPath);

        // 5. 버전 메타데이터 DB 저장 (maps to VersionEntity insert)
        VersionInfo version;
        version.versionId = versionId;
        version.fileId = fileId;
        version.userId = userId;
        version.timestamp = timestamp;
        version.size = content.size();
        version.mimeType = content.mimeType;

        // DB: files_versions 테이블에 INSERT
        // 인덱스 (file_id, timestamp)
        // C++에서는 Initializer List 내 타입이 동일해야 하므로 문자열로 변환하여 전달
        // 02/10 - user_id 컬럼 추가, metadata에 author 저장
        // 03/13 - JSON 수동 조립 → buildVersionMetadataJson 헬퍼 사용 (특수문자 이스케이프)
        std::string metadata = buildVersionMetadataJson(userId);
        db->execute("INSERT INTO files_versions (file_id, user_id, timestamp, size, mimetype, metadata) "
                    "VALUES (?, ?, ?, ?, ?, ?)",
                    {fileId, userId, std::to_string(timestamp), std::to_string(version.size), version.mimeType, metadata});

        // 6. 활동 로그 기록 (maps to Activity logging)
        auditLog->logActivity(userId, fileId, "version_created", "Initial version");

        return version;
    }
    // 트랜잭션 처리 문제가 있다. 파일쓰기, 복사, db저장이 독립적으로 실행되기에 데이터 불일치 상태가 발생할 가능성이 있음

    // RD-SRS-9.2: 문서 수정 시 버전이 자동으로 업데이트되어야 함
    // code: apps/files_versions/lib/Listener/FileEventsListener.php
    //       apps/files_versions/lib/Events/CreateVersionEvent.php
    //       apps/files_versions/lib/Storage.php (store)
    // Triggered by: NodeWrittenEvent from file modification
    VersionInfo onDocumentModified(const std::string& userId,
                                    const std::string& fileId,
                                    const FileContent& newContent) {
        // 1. 이전 버전 백업 전 이벤트 발생 (maps to CreateVersionEvent)
        // Nextcloud에서는 이벤트 리스너가 자동으로 처리하지만,
        //       여기서는 명시적 API 호출로 표현

        // 2. 현재 파일을 버전으로 저장 (maps to FileEventsListener::handle)
        auto currentContent = fileStorage->readFile(fileId);
        // 02/10 - 9.1과 마찬가지로 초 단위로 통일
        auto timestamp = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();
        // auto timestamp = std::chrono::system_clock::now().time_since_epoch().count();
        auto versionId = fileId + ".v" + std::to_string(timestamp);

        // 3. 버전 스냅샷 생성 (maps to Storage::store)
        // 02/10 - TOCTOU 방지: copyFile 대신 readFile 결과를 writeFile로 저장
        std::string versionPath = "files_versions/" + versionId;
        fileStorage->writeFile(versionPath, currentContent);
        // fileStorage->copyFile(fileId, versionPath);

        // 4. 새 콘텐츠로 원본 파일 업데이트
        fileStorage->writeFile(fileId, newContent);

        // 5. 버전 정보 DB 저장
        VersionInfo version;
        version.versionId = versionId;
        version.fileId = fileId;
        version.userId = userId;
        version.timestamp = timestamp;
        version.size = currentContent.size();
        version.mimeType = currentContent.mimeType;

        // 02/10 - user_id 컬럼 추가, metadata에 author 저장
        // 03/13 - JSON 수동 조립 → buildVersionMetadataJson 헬퍼 사용 (특수문자 이스케이프)
        std::string metadata = buildVersionMetadataJson(userId);
        db->execute("INSERT INTO files_versions (file_id, user_id, timestamp, size, mimetype, metadata) "
                    "VALUES (?, ?, ?, ?, ?, ?)",
                    {fileId, userId, std::to_string(timestamp), std::to_string(version.size), version.mimeType, metadata});

        // 6. 버전 생성 완료 이벤트 (maps to VersionCreatedEvent)
        workflowEngine->dispatchEvent("version_created", {{"fileId", fileId}, {"versionId", versionId}});

        // 7. 활동 로그
        // 02/10 - 변경 내용을 포함한 로그 메시지
        // 03/13 - 사이즈 기반 로그 → diff summary로 교체
        // 기존 문제: 사이즈가 동일한 악의적 변경을 탐지 불가
        // 해결: DiffService로 실제 변경 내용(추가/삭제 줄 수)을 기록
        std::string logMsg;
        if (diffService != nullptr) {
            DiffResult diffResult = diffService->computeDiff(currentContent, newContent);
            logMsg = "Modified: " + diffResult.summary;
            // diff 결과를 버전 메타데이터에도 기록 (추후 DB 저장 시 활용)
            // TODO: version_diffs 테이블에 diffResult 저장 (인프라 연동 시)
        } else {
            // fallback: DiffService 미연결 시 기존 사이즈 기반 로그 유지
            logMsg = "Modified: size " + std::to_string(currentContent.size()) +
                    " -> " + std::to_string(newContent.size()) + " bytes";
        }
        auditLog->logActivity(userId, fileId, "file_modified", logMsg);

        // 8. 버전 정책 적용 (자동 정리)
        policyManager->applyRetentionPolicy(fileId);

        return version;
    }
    // 자동 트리거 메커니즘 없음 -> framework 연동을 통하여 이벤트 리스너 필요 가능성 생각

    // RD-SRS-9.3: 문서 변경 이력에는 수정자, 수정 시각, 변경 내용, 변경 이유가 포함되어야 함
    // code: apps/activity/lib/Data.php (send)
    //       apps/admin_audit/lib/Files.php
    //       apps/files_versions/lib/Db/VersionEntity.php (metadata)
    // 변경 이유(reason)는 Nextcloud 기본 구현에 없어 커스텀 확장 필요
    void logDocumentChangeHistory(const std::string& userId,
                                const std::string& fileId,
                                const std::string& action,
                                const std::string& reason = "") {
        // 1. Activity 앱을 통한 기록 (maps to Activity\Data::send)
        ActivityEntry activity;
        activity.userId = userId;
        activity.action = action;
        // 02/10 - 9.1과 동일하게 초 단위로 통일
        activity.timestamp = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();
        activity.subject = "file_" + action;
        activity.objectType = "files";
        activity.objectId = fileId;
        // activity.message 미사용 문제
        // 02/10 - message 필드 설정
        activity.message = action + (reason.empty() ? "" : " - Reason: " + reason);

        // reason 필드는 Nextcloud 기본 스키마에 없음
        // 커스텀 구현: metadata JSON 필드 또는 별도 테이블 필요
        if (!reason.empty()) {
            activity.reason = reason;
            // 버전 메타데이터에 이유 저장 (maps to VersionEntity::setMetadataValue)
            // 02/11 - ORDER BY ... LIMIT을 서브쿼리로 대체 (PostgreSQL 호환을 위하여)
            db->execute("UPDATE files_versions SET metadata = JSON_SET(metadata, '$.reason', ?) "
                        "WHERE file_id = ? AND timestamp = "
                        "(SELECT MAX(timestamp) FROM files_versions WHERE file_id = ?)",
                        {reason, fileId, fileId});
        }

        // 2. Activity 테이블에 저장
        // 02/10 - 구조체 값 사용으로 일관성 확보
        db->execute("INSERT INTO activity (timestamp, user, affecteduser, app, subject, "
                    "subjectparams, file, object_type, object_id) "
                    "VALUES (?, ?, ?, 'files', ?, ?, ?, ?, ?)",
                    {std::to_string(activity.timestamp), activity.userId, activity.userId, activity.subject,
                    "{}", activity.objectId, activity.objectType, activity.objectId});
        // DB INSERT할 때는 구조체를 안 쓰고 직접 값을 넣음 e.g., activity.userId 안 씀

        // 3. Admin Audit 로그 (maps to Admin_Audit\Files)
        if (isAdminAuditEnabled()) {
            std::string auditLogMsg = "[" + std::to_string(activity.timestamp) + "] "
                                + "User: " + userId + ", Action: " + action
                                + ", File: " + fileId;
            if (!reason.empty()) {
                auditLogMsg += ", Reason: " + reason;
            }
            writeAuditLog(auditLogMsg);
        }

        // 4. 이벤트 발생 (다른 모듈 연동용)
        workflowEngine->dispatchEvent("file_activity", {
            {"userId", userId},
            {"fileId", fileId},
            {"action", action},
            {"reason", reason}
        });
    }
    // 변경 내용 사항은 구현되지 않았음을 확인 추후 개발할 필요성 생각
    // 9.2와 마찬가지로 트랜잭션 없음 -> 데이터 불일치 가능성 존재

    // RD-SRS-9.4: 이전 버전과 현재 버전 간 차이를 비교할 수 있어야 함
    // code: apps/files_versions/lib/Sabre/VersionFile.php (get)
    //       apps/files_versions/lib/Storage.php (getVersions)
    // Called from desktop via: GET /remote.php/dav/versions/{user}/versions/{fileId}/{versionId}
    // 03/13 - 기존: 서버는 콘텐츠만 제공, diff는 클라이언트 담당
    //         변경: DiffService를 통해 서버 측에서 diff 계산 후 결과를 포함하여 반환
    DiffInfo prepareVersionComparison(const std::string& userId,
                                    const std::string& fileId,
                                    const std::string& versionId1,
                                    const std::string& versionId2) {
        DiffInfo diff;
        diff.versionId1 = versionId1;
        diff.versionId2 = versionId2;

        // 1. 첫 번째 버전 콘텐츠 조회 (maps to VersionFile::get)
        std::string versionPath1 = "files_versions/" + versionId1;
        if (versionId1 == "current") {
            diff.content1 = fileStorage->readFile(fileId);
        } else {
            diff.content1 = fileStorage->readFile(versionPath1);
        }

        // 2. 두 번째 버전 콘텐츠 조회
        std::string versionPath2 = "files_versions/" + versionId2;
        if (versionId2 == "current") {
            diff.content2 = fileStorage->readFile(fileId);
        } else {
            diff.content2 = fileStorage->readFile(versionPath2);
        }

        // 3. 서버 측 diff 계산 (03/13 추가)
        // DiffService를 통해 Myers diff(텍스트) 또는 SHA-256 비교(바이너리) 수행
        // 결과는 DiffResult에 unified diff, 통계, 요약을 포함
        if (diffService != nullptr) {
            diff.diffResult = diffService->computeDiff(diff.content1, diff.content2);
        }
        // diffService가 nullptr인 경우: 콘텐츠만 반환 (기존 동작 유지, 하위 호환)

        // 03/13 - Dead Code 제거:
        // 기존에 versionInfo1, versionInfo2를 DB에서 조회했으나
        // 조회 결과를 어디에도 사용하지 않고 있었음 (읽기만 하고 반환/활용 없음)
        // 메타데이터가 필요한 경우 getVersionsAtTime(9.5)을 별도 호출하는 것이 적절
        // → 불필요한 DB 쿼리 2회 제거

        // 4. 활동 로그
        // 03/13 - diff 요약이 있으면 로그에 포함
        std::string logMsg = versionId1 + " vs " + versionId2;
        if (diffService != nullptr && !diff.diffResult.summary.empty()) {
            logMsg += " (" + diff.diffResult.summary + ")";
        }
        auditLog->logActivity(userId, fileId, "version_compared", logMsg);

        return diff;
    }

    // RD-SRS-9.5: 특정 시점의 문서 버전을 확인하고 조회할 수 있어야 함
    // code: apps/files_versions/lib/Storage.php (getVersions)
    //       apps/files_versions/lib/Versions/IVersion.php (getTimestamp)
    //       apps/files_versions/lib/Sabre/VersionCollection.php
    // Called from desktop via: PROPFIND /remote.php/dav/versions/{user}/versions/{fileId}/
    std::vector<VersionInfo> getVersionsAtTime(const std::string& userId,
                                                const std::string& fileId,
                                                int64_t targetTimestamp,
                                                int limit = 10) {
        std::vector<VersionInfo> versions;

        // 1. 모든 버전 목록 조회 (maps to Storage::getVersions)
        // 02/10 - LIMIT 값을 직접 삽입 (바인딩 파라미터 호환성 문제 방지)
        auto results = db->query(
            "SELECT file_id, user_id, timestamp, size, mimetype, metadata "
            "FROM files_versions "
            "WHERE file_id = ? AND timestamp <= ? "
            "ORDER BY timestamp DESC "
            "LIMIT " + std::to_string(limit), 
            {fileId, std::to_string(targetTimestamp)}
        );                      

        // 2. 각 버전 정보 구성 (maps to IVersion interface)
        for (const auto& row : results) {
            VersionInfo version;
            version.fileId = row.at("file_id");
            version.versionId = version.fileId + ".v" + row.at("timestamp");
            version.timestamp = std::stoll(row.at("timestamp"));
            version.size = std::stoull(row.at("size"));
            version.mimeType = row.at("mimetype");

            // 버전 작성자 조회 (maps to VersionAuthorListener)
            // 02/10 - user_id 컬럼 우선, metadata fallback
            auto metadata = parseJson(row.at("metadata"));
            std::string userId_col = row.count("user_id") ? row.at("user_id") : "";
            if (!userId_col.empty()) {
                version.userId = userId_col;
            } else {
                version.userId = metadata.count("author") ? metadata["author"] : "unknown";
            }
            version.metadata = metadata;

            versions.push_back(version);
        }

        // 3. 특정 시점에 가장 가까운 버전 찾기 (maps to VersionCollection)
        if (versions.empty() && targetTimestamp > 0) {
            // 지정된 시간보다 이후의 가장 오래된 버전 찾기
            auto futureResults = db->query(
                "SELECT * FROM files_versions "
                "WHERE file_id = ? AND timestamp > ? "
                "ORDER BY timestamp ASC LIMIT 1",
                {fileId, std::to_string(targetTimestamp)}
            );

            if (!futureResults.empty()) {
                // 위와 동일한 방식으로 VersionInfo 구성
                const auto& row = futureResults[0];
                VersionInfo version;
                version.fileId = row.at("file_id");
                version.versionId = version.fileId + ".v" + row.at("timestamp");
                version.timestamp = std::stoll(row.at("timestamp"));
                version.size = std::stoull(row.at("size"));
                version.mimeType = row.at("mimetype");
                // 02/10 - user_id 컬럼 우선, metadata fallback
                auto metadata = parseJson(row.at("metadata"));
                std::string userId_col = row.count("user_id") ? row.at("user_id") : "";
                if (!userId_col.empty()) {
                    version.userId = userId_col;
                } else {
                    version.userId = metadata.count("author") ? metadata["author"] : "unknown";
                }
                version.metadata = metadata;
                
                versions.push_back(version);
            }
        }

        // 4. 활동 로그
        auditLog->logActivity(userId, fileId, "versions_listed", "at_time:" + std::to_string(targetTimestamp));

        return versions;
    }

    // RD-SRS-9.6: 문서 상태 관리 (초안, 검토중, 승인됨, 폐기 등)
    // code: apps/systemtags/lib/Controller/LastUsedController.php
    //       lib/public/SystemTag/ISystemTagManager.php (createTag/updateTag)
    //       lib/public/SystemTag/ISystemTagObjectMapper.php (assignTags)
    // Called via: OCS API POST /ocs/v2.php/apps/systemtags
    bool setDocumentStatus(const std::string& userId,
                        const std::string& fileId,
                        DocumentStatus status,
                        const std::string& comment = "") {
        // 1. 상태를 태그 이름으로 매핑
        // 03/05 - 하드코딩된 문자열을 클래스 상수로 교체
        std::string tagName;
        switch (status) {
            case DocumentStatus::DRAFT: tagName = TAG_DRAFT; break;
            case DocumentStatus::UNDER_REVIEW: tagName = TAG_UNDER_REVIEW; break;
            case DocumentStatus::APPROVED: tagName = TAG_APPROVED; break;
            case DocumentStatus::REJECTED: tagName = TAG_REJECTED; break;
            case DocumentStatus::DEPRECATED: tagName = TAG_DEPRECATED; break;
            // 02/10 - default case 추가 - 잘못된 enum 값 방어를 위함
            default:
                throw std::invalid_argument("Unknown DocumentStatus: " + std::to_string(static_cast<int>(status)));
        }

        // 2. 상태 전이 유효성 검사
        // 03/05 - DLP 보안: 허용되지 않은 상태 전이를 차단
        // ※ 전이 규칙은 초기 버전이며, 설계 단계에서 재검토 예정 (isValidTransition 주석 참조)
        std::string currentTag = getCurrentStatusTag(fileId);
        if (!isValidTransition(currentTag, tagName)) {
            std::string currentDisplay = currentTag.empty() ? "(none)" : currentTag;
            auditLog->logActivity(userId, fileId, "status_change_denied",
                                "Invalid transition: " + currentDisplay + " -> " + tagName);
            return false;
        }

        // 3. 태그가 존재하는지 확인, 없으면 생성 (maps to ISystemTagManager::createTag)
        auto tagResult = db->query("SELECT id FROM systemtag WHERE name = ?", {tagName});
        std::string tagId;

        if (tagResult.empty()) {
            // 새 태그 생성
            tagId = generateUUID();
            db->execute("INSERT INTO systemtag (id, name, visibility, editable) "
                        "VALUES (?, ?, 1, 1)", {tagId, tagName});
        } else {
            tagId = tagResult[0]["id"];
        }

        // 4. 기존 상태 태그 제거 (한 파일은 하나의 상태만 가질 수 있도록)
        std::vector<std::string> statusTags = {TAG_DRAFT, TAG_UNDER_REVIEW, TAG_APPROVED, TAG_REJECTED, TAG_DEPRECATED};
        for (const auto& oldTag : statusTags) {
            if (oldTag != tagName) {
                db->execute("DELETE FROM systemtag_object_mapping "
                            "WHERE objectid = ? AND objecttype = 'files' "
                            "AND systemtagid IN (SELECT id FROM systemtag WHERE name = ?)",
                            {fileId, oldTag});
            }
        }

        // 5. 새 상태 태그 할당 (maps to ISystemTagObjectMapper::assignTags)
        db->execute("INSERT OR REPLACE INTO systemtag_object_mapping "
                    "(objectid, objecttype, systemtagid) "
                    "VALUES (?, 'files', ?)", {fileId, tagId});

        // 6. 상태 변경 이력 기록
        logDocumentChangeHistory(userId, fileId, "status_changed", "Changed to " + tagName + ": " + comment);

        // 7. 워크플로우 트리거 (maps to WorkflowEngine check)
        // 태그 변경이 워크플로우 조건으로 사용될 수 있음
        workflowEngine->evaluateRules("tag_assigned", {
            {"fileId", fileId},
            {"tagName", tagName},
            {"userId", userId}
        });

        // 8. 알림 발송 (상태 변경 알림)
        if (status == DocumentStatus::APPROVED || status == DocumentStatus::DEPRECATED) {
            notificationService->notifyFileSubscribers(fileId,
                "Document status changed to " + tagName, userId);
        }

        return true;
    }

    // RD-SRS-9.7: 문서 승인 워크플로우 및 승인 프로세스 관리
    // code: apps/approval/lib/Service/RuleService.php (createRule, checkRule, storeAction)
    //       apps/workflowengine/lib/Manager.php
    //       apps/notifications (알림 발송)
    // Approval 앱은 별도 저장소이므로 기본 기능을 모방
    bool processApprovalWorkflow(const std::string& userId,
                                const std::string& fileId,
                                ApprovalAction action,
                                const std::string& comment = "",
                                const std::vector<std::string>& approvers = {}) {
        bool success = false;

        switch (action) {
            case ApprovalAction::REQUEST: {
                // 03/05 - 선행 검증 1: 빈 승인자 목록 방어
                // 승인자가 없으면 누구도 APPROVE/REJECT할 수 없어 문서가 UNDER_REVIEW에 영구 체류
                if (approvers.empty()) {
                    auditLog->logActivity(userId, fileId, "approval_failed",
                                        "No approvers specified for approval request");
                    break;  // success = false 유지
                }

                // 03/05 - 선행 검증 2: 태그 기반 중복 승인 요청 방어 (Nextcloud 방식)
                // pending 태그(under_review)가 이미 할당되어 있으면 승인 대기 중이므로 중복 요청 거부
                // systemtag_object_mapping이 Single Source of Truth (setDocumentStatus가 관리)
                auto pendingCheck = db->query(
                    "SELECT systemtagid FROM systemtag_object_mapping "
                    "WHERE objectid = ? AND objecttype = 'files' "
                    "AND systemtagid IN (SELECT id FROM systemtag WHERE name = ?)",
                    {fileId, TAG_UNDER_REVIEW}
                );
                if (!pendingCheck.empty()) {
                    auditLog->logActivity(userId, fileId, "approval_failed",
                                        "Approval already pending for this file");
                    break;  // success = false 유지
                }

                // 1. pending 태그 할당 (승인 요청 시작)
                setDocumentStatus(userId, fileId, DocumentStatus::UNDER_REVIEW,
                                "Approval requested: " + comment);

                // 2. 승인 규칙 생성/확인 (maps to RuleService::createRule)
                // 02/10 - file_id를 규칙에 연결하여 파일별 승인 관리
                // 03/05 - 태그 이름을 클래스 상수로 파라미터화
                std::string ruleId = generateUUID();
                db->execute("INSERT INTO approval_rules (id, file_id, tag_pending, tag_approved, tag_rejected) "
                            "VALUES (?, ?, ?, ?, ?)", {ruleId, fileId, TAG_UNDER_REVIEW, TAG_APPROVED, TAG_REJECTED});

                // 3. 요청자 등록 (maps to approval_rule_requesters)
                db->execute("INSERT INTO approval_rule_requesters (rule_id, entity_type, entity_id) "
                            "VALUES (?, 'user', ?)", {ruleId, userId});

                // 4. 승인자 등록 (maps to approval_rule_approvers)
                for (const auto& approver : approvers) {
                    db->execute("INSERT INTO approval_rule_approvers (rule_id, entity_type, entity_id) "
                                "VALUES (?, 'user', ?)", {ruleId, approver});

                    // 5. 승인자에게 알림 발송 (maps to Notification::Manager::notify)
                    notificationService->sendNotification(approver,
                        "Approval requested for file " + fileId,
                        "User " + userId + " requested your approval. Comment: " + comment,
                        {"push", "email", "web"});
                }

                success = true;
                break;
            }

            case ApprovalAction::APPROVE: {
                // 03/13 - APPROVE/REJECT 공통 로직을 processApprovalDecision으로 추출
                success = processApprovalDecision(
                    userId, fileId, comment,
                    DocumentStatus::APPROVED, TAG_APPROVED,
                    "Your document was approved",
                    "approved"
                );

                // APPROVE 전용: 다음 워크플로우 단계 트리거 (체인 워크플로우)
                if (success) {
                    workflowEngine->evaluateRules("document_approved", {
                        {"fileId", fileId},
                        {"approverId", userId}
                    });
                }
                break;
            }

            case ApprovalAction::REJECT: {
                // 03/13 - APPROVE/REJECT 공통 로직을 processApprovalDecision으로 추출
                success = processApprovalDecision(
                    userId, fileId, comment,
                    DocumentStatus::REJECTED, TAG_REJECTED,
                    "Your document was rejected",
                    "rejected"
                );
                break;
            }

            // 03/05 - default case 추가 (setDocumentStatus와 동일한 방어 패턴)
            default:
                throw std::invalid_argument("Unknown ApprovalAction: " + std::to_string(static_cast<int>(action)));
        }

        return success;
    }

    // RD-SRS-9.9: 문서 변경 시 관련 이해관계자에게 자동 알림
    // code: lib/private/Notification/Manager.php (createNotification, notify)
    //       apps/notifications/lib/Push.php (pushToDevice)
    //       apps/notifications/lib/BackgroundJob/SendNotificationMails.php
    // Triggered by: 파일 변경 이벤트, 워크플로우 이벤트
    bool notifyStakeholders(const std::string& fileId,
                            const std::string& eventType,
                            const std::string& message,
                            const std::vector<NotificationTarget>& targets) {
        // 1. 알림 객체 생성 (maps to Notification\Manager::createNotification)
        // 02/10 - 이벤트 timestamp를 루프 밖에서 한 번만 생성
        auto eventTimestamp = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();
        
        for (const auto& target : targets) {
            std::string notificationId = generateUUID();

            // 2. 알림 데이터 준비
            std::map<std::string, std::string> notificationData = {
                {"app", "files"},
                {"user", target.userId},
                {"timestamp", std::to_string(eventTimestamp)},
                {"object_type", "files"},
                {"object_id", fileId},
                {"subject", eventType},
                {"message", message}
            };

            // 3. DB에 알림 저장 (maps to notifications table)
            db->execute("INSERT INTO notifications (notification_id, app, user, timestamp,"
                        "object_type, object_id, subject, message) "
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        {notificationId, "files", target.userId, std::to_string(eventTimestamp),
                        "files", fileId, eventType, message});

            // 4. 채널별 전달 (maps to various delivery mechanisms)
            for (const auto& channel : target.channels) {
                if (channel == "push") {
                    // 푸시 알림 (maps to Push::pushToDevice)
                    auto pushTokens = db->query("SELECT token FROM notifications_pushhash "
                                                "WHERE uid = ?", {target.userId});
                    for (const auto& token : pushTokens) {
                        sendPushNotification(token.at("token"), message);
                    }

                } else if (channel == "email") {
                    // 이메일 큐에 추가 (maps to mail queue)
                    db->execute("INSERT INTO notifications_mq (amq_timestamp, amq_affecteduser, "
                                "amq_appid, amq_subject, amq_subjectparams) "
                                "VALUES (?, ?, 'files', ?, ?)",
                                {std::to_string(eventTimestamp), target.userId, eventType, message});

                } else if (channel == "web") {
                    // 웹 알림은 이미 notifications 테이블에 저장됨
                    // 클라이언트가 폴링하거나 WebSocket으로 수신
                }
            }
        }

        // 5. 활동 로그
        auditLog->logActivity("system", fileId, "notifications_sent",
                            "Event: " + eventType + ", Recipients: " + std::to_string(targets.size()));

        // 6. 배치 처리 트리거 (필요한 경우)
        // 실제로는 BackgroundJob이 주기적으로 실행
        if (shouldBatchNotifications()) {
            scheduleBackgroundJob("SendNotificationMails");
        }

        return true;
    }
    // 현재 수동 호출 방식으로 구현되어 있음 추후 framework 연동을 통하여 이벤트 연동을 통하여 자동으로 호출할 수 있도록 구현이 필요

    // RD-SRS-9.10: 문서 버전 관리 정책 구성 (보존 기간, 최대 버전 수 등)
    // code: apps/files_versions/lib/Expiration.php (getExpireList)
    //       apps/files_versions/lib/Storage.php (expire)
    //       apps/files_versions/lib/BackgroundJob/ExpireVersions.php
    // Config: versions_retention_obligation (auto, D/auto, auto/D, D1/D2)
    int applyVersionRetentionPolicy(const std::string& fileId,
                                    const RetentionPolicy& policy) {
        int deletedVersions = 0;

        // 1. 현재 정책 읽기 (maps to config versions_retention_obligation)
        // 02/10 - policyString을 로그에 활용 (원래 dead code였음)
        std::string policyString = policy.autoCleanup ? "auto" : "";
        if (policy.minDays > 0 && policy.maxDays > 0) {
            policyString = std::to_string(policy.minDays) + "/" + std::to_string(policy.maxDays);
        } else if (policy.maxDays > 0) {
            policyString = "auto/" + std::to_string(policy.maxDays);
        }
        // policyString은 아래 정리 작업 로그에서 사용됨

        // 2. 파일의 모든 버전 조회 (maps to Storage::getVersions)
        auto versions = db->query("SELECT file_id, timestamp, size FROM files_versions "
                                "WHERE file_id = ? ORDER BY timestamp DESC", {fileId});

        // 3. 보존할 버전과 삭제할 버전 결정 (maps to Expiration::getExpireList)
        // 03/05 - vector → unordered_set: 할당량 정리 시 중복 체크를 O(1)로 개선
        std::unordered_set<std::string> toDeleteSet;
        // 02/10 - 초 단위로 통일
        auto now = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();

        // 계층적 보존 전략 구현
        // 03/05 - 버전 나이 기반 간격 선택으로 수정 (기존: 작은 interval부터 순회하여 계층 무력화)
        // 나이별 적용 간격은 getRequiredInterval() 참조

        int versionCount = 0;
        int64_t lastKeptTimestamp = 0;

        for (const auto& version : versions) {
            versionCount++;
            int64_t vTimestamp = std::stoll(version.at("timestamp"));
            int64_t versionAge = now - vTimestamp;

            // 최근 2개 버전은 항상 보존
            if (versionCount <= 2) {
                lastKeptTimestamp = vTimestamp;
                continue;
            }

            // 최대 버전 수 체크
            if (policy.maxVersions > 0 && versionCount > policy.maxVersions) {
                toDeleteSet.insert(version.at("file_id") + ".v" + version.at("timestamp"));
                continue;
            }

            // 최대 보관 기간 체크
            // 02/10 - 초 단위 통일 - 86400초 = 1일
            if (policy.maxDays > 0 && versionAge > (int64_t)policy.maxDays * 86400) {
                toDeleteSet.insert(version.at("file_id") + ".v" + version.at("timestamp"));
                continue;
            }

            // 최소 보관 기간 내의 버전은 보존
            // 02/10 - 초 단위 통일
            if (policy.minDays > 0 && versionAge < (int64_t)policy.minDays * 86400) {
                lastKeptTimestamp = vTimestamp;
                continue;
            }

            // 계층적 간격 체크
            // 03/05 - 버전 나이에 따라 적절한 간격을 선택하여 비교
            // (기존: retentionIntervals를 작은 값부터 순회 → 2초 조건에서 항상 통과하는 버그)
            int64_t requiredInterval = getRequiredInterval(versionAge);

            if (lastKeptTimestamp - vTimestamp >= requiredInterval) {
                // 마지막 보존 버전과 충분한 간격 → 보존
                lastKeptTimestamp = vTimestamp;
            } else if (policy.autoCleanup) {
                // 간격 부족 → 삭제 대상
                toDeleteSet.insert(version.at("file_id") + ".v" + version.at("timestamp"));
            }
        }

        // 4. 할당량 기반 추가 정리 (maps to quota-based cleanup)
        if (policy.autoCleanup) {
            size_t totalSize = 0;
            for (const auto& version : versions) {
                totalSize += std::stoull(version.at("size"));
            }

            size_t quotaLimit = getQuotaLimit();
            if (totalSize > quotaLimit) {
                // 02/10 - size_t underflow 방지: 3개 미만이면 정리 대상 없음
                if (versions.size() >= 3) {
                    // int 캐스팅으로 unsigned underflow 방지
                    for (int i = static_cast<int>(versions.size()) - 1; i >= 2 && totalSize > quotaLimit; i--) {
                        auto versionId = versions[i].at("file_id") + ".v" + versions[i].at("timestamp");
                        // 03/05 - unordered_set::find로 중복 체크 O(1) (기존: std::find O(n))
                        if (toDeleteSet.find(versionId) == toDeleteSet.end()) {
                            toDeleteSet.insert(versionId);
                            totalSize -= std::stoull(versions[i].at("size"));
                        }
                    } // end for
                } // end of versionsSize >= 3 check
            }
        }

        // 5. 버전 삭제 실행 (maps to Storage::expire)
        for (const auto& versionId : toDeleteSet) {
            // 파일 시스템에서 삭제
            fileStorage->deleteFile("files_versions/" + versionId);

            // DB에서 삭제
            auto timestamp = extractTimestamp(versionId);
            db->execute("DELETE FROM files_versions WHERE file_id = ? AND timestamp = ?",
                        {fileId, timestamp});

            deletedVersions++;
        }

        // 6. 정리 작업 로그
        if (deletedVersions > 0) {
            // 공백 추가, policyString 활용
            auditLog->logActivity("system", fileId, "versions_expired",
                                "Deleted " + std::to_string(deletedVersions) + " versions, policy: " + policyString);
        }

        return deletedVersions;
    }

private:
    // 헬퍼 함수들
    // 정적 카운터로 UUID 고유성 보장
    int uuidCounter = 0;
    std::string generateUUID() {
        // UUID 생성 로직
        // 02/10 - timestamp + 카운터로 빠른 연속 생성에서도 고유성 보장
        return "uuid_" + std::to_string(std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count()) + "_" + std::to_string(uuidCounter++);
    }

    std::string extractTimestamp(const std::string& versionId) {
        // versionId에서 타임스탬프 추출 (예: "fileId.v123456" → "123456")
        auto pos = versionId.find(".v");
        if (pos != std::string::npos) {
            return versionId.substr(pos + 2);
        }
        return "";
    }

    // 03/13 - APPROVE/REJECT 공통 로직 추출
    // 두 case의 구조가 거의 동일하여 코드 중복 제거 목적으로 분리
    // 차이점: DocumentStatus, TAG 상수, 알림 텍스트만 다름
    // APPROVE 전용 후처리(workflowEngine)는 호출부에서 처리
    //
    // 매개변수:
    //   userId     - 승인/거절 수행자
    //   fileId     - 대상 파일 ID
    //   comment    - 승인/거절 사유
    //   newStatus  - 변경할 상태 (APPROVED 또는 REJECTED)
    //   actionTag  - 액션 기록용 태그 (TAG_APPROVED 또는 TAG_REJECTED)
    //   notifySubject - 알림 제목 ("Your document was approved/rejected")
    //   actionVerb - 로그/알림용 동사 ("approved" 또는 "rejected")
    // 반환: 성공 여부
    bool processApprovalDecision(const std::string& userId,
                                  const std::string& fileId,
                                  const std::string& comment,
                                  DocumentStatus newStatus,
                                  const char* actionTag,
                                  const std::string& notifySubject,
                                  const std::string& actionVerb) {
        // 1. 승인/거절 권한 확인
        auto approverCheck = db->query(
            "SELECT rule_id FROM approval_rule_approvers "
            "WHERE entity_id = ? AND rule_id IN "
            "(SELECT id FROM approval_rules WHERE tag_pending = ? AND file_id = ?)",
            {userId, TAG_UNDER_REVIEW, fileId}
        );

        if (approverCheck.empty()) {
            return false;  // 권한 없음
        }

        std::string ruleId = approverCheck[0]["rule_id"];

        // 2. 상태 태그 변경
        std::string statusComment = actionVerb.substr(0, 1);
        // 첫 글자 대문자로: "approved" → "Approved"
        statusComment = static_cast<char>(std::toupper(static_cast<unsigned char>(actionVerb[0])))
                        + actionVerb.substr(1) + ": " + comment;
        setDocumentStatus(userId, fileId, newStatus, statusComment);

        // 3. 승인/거절 액션 기록 (maps to RuleService::storeAction)
        auto timestamp = std::to_string(std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()).count());
        db->execute("INSERT INTO approval_activity (rule_id, user_id, action, "
                    "timestamp, comment) VALUES (?, ?, ?, ?, ?)",
                    {ruleId, userId, actionTag, timestamp, comment});

        // 4. 요청자에게 알림
        auto requester = db->query(
            "SELECT entity_id FROM approval_rule_requesters WHERE rule_id = ?",
            {ruleId}
        );
        if (!requester.empty()) {
            std::string notifyBody = "File " + fileId + " has been " + actionVerb + " by " + userId;
            if (!comment.empty() && actionVerb == "rejected") {
                notifyBody += ". Comment: " + comment;
            }
            notificationService->sendNotification(requester[0]["entity_id"],
                notifySubject, notifyBody, {"push", "email", "web"});
        }

        return true;
    }

    // 03/05 - 파일의 현재 문서 상태를 태그 기반으로 조회
    // 반환: 현재 상태 태그 이름 (예: "draft", "approved")
    //       태그가 없으면 빈 문자열 (새 파일이거나 상태 미지정)
    std::string getCurrentStatusTag(const std::string& fileId) {
        std::vector<std::string> statusTags = {TAG_DRAFT, TAG_UNDER_REVIEW, TAG_APPROVED, TAG_REJECTED, TAG_DEPRECATED};
        for (const auto& tag : statusTags) {
            auto result = db->query(
                "SELECT systemtagid FROM systemtag_object_mapping "
                "WHERE objectid = ? AND objecttype = 'files' "
                "AND systemtagid IN (SELECT id FROM systemtag WHERE name = ?)",
                {fileId, tag}
            );
            if (!result.empty()) {
                return tag;
            }
        }
        return "";  // 상태 없음 (새 파일)
    }

    // 03/05 - 상태 전이 유효성 검사
    // DLP 보안 정책: 허용되지 않은 상태 전이를 API 레벨에서 차단
    //
    // ※ 주의: 현재 전이 규칙은 확정되지 않은 초기 버전입니다.
    //   - 설계 단계(4~6월)에서 실제 비즈니스 요구사항에 맞춰 재검토 예정
    //   - 라이브러리로 제공 시, 도입 기업이 커스터마이징할 수 있는
    //     설정 인터페이스(전이 매트릭스 오버라이드) 추가를 고려 중
    //   - 특히 DEPRECATED의 최종 상태 여부, APPROVED→DRAFT 허용 여부 등은
    //     도입 기업의 보안 정책에 따라 달라질 수 있음
    //
    // 현재 기본 전이 규칙:
    //   (없음)       → DRAFT, UNDER_REVIEW       새 파일 최초 상태 설정
    //   DRAFT        → UNDER_REVIEW, DEPRECATED   검토 요청 또는 폐기
    //   UNDER_REVIEW → APPROVED, REJECTED          승인자만 결정
    //   APPROVED     → DEPRECATED                  승인 후 폐기만 가능
    //   REJECTED     → DRAFT                       수정 후 재제출만 가능
    //   DEPRECATED   → (전이 불가)                 최종 상태
    bool isValidTransition(const std::string& currentTag, const std::string& newTag) {
        // 전이 매트릭스: {현재 상태, {허용되는 다음 상태들}}
        static const std::map<std::string, std::vector<std::string>> transitionMatrix = {
            {"",                {TAG_DRAFT, TAG_UNDER_REVIEW}},                 // 새 파일
            {TAG_DRAFT,         {TAG_UNDER_REVIEW, TAG_DEPRECATED}},            // 초안
            {TAG_UNDER_REVIEW,  {TAG_APPROVED, TAG_REJECTED}},                  // 검토중
            {TAG_APPROVED,      {TAG_DEPRECATED}},                              // 승인됨
            {TAG_REJECTED,      {TAG_DRAFT}},                                   // 거절됨
            {TAG_DEPRECATED,    {}},                                            // 폐기 (최종)
        };

        auto it = transitionMatrix.find(currentTag);
        if (it == transitionMatrix.end()) {
            return false;  // 알 수 없는 현재 상태
        }

        const auto& allowed = it->second;
        return std::find(allowed.begin(), allowed.end(), newTag) != allowed.end();
    }

    bool isAdminAuditEnabled() {
        // Admin Audit 활성화 여부 확인
        return true;  // 간단히 true 반환
    }

    void writeAuditLog(const std::string& message) {
        // 감사 로그 파일에 쓰기
        // 실제 구현에서는 파일 I/O 처리
    }

    bool shouldBatchNotifications() {
        // 알림 배치 처리 여부 결정
        return true;
    }

    void scheduleBackgroundJob(const std::string& jobName) {
        // 백그라운드 작업 스케줄링
        // 실제로는 cron job 또는 큐 시스템 사용
    }

    void sendPushNotification(const std::string& token, const std::string& message) {
        // 푸시 알림 발송 (FCM/APNS)
        // 실제 구현에서는 푸시 서비스 API 호출
    }

    size_t getQuotaLimit() {
        // 사용자 할당량 조회
        return 10ULL * 1024 * 1024 * 1024;  // 예: 10GB (ULL 접미사로 오버플로우 방지)
    }

    // 03/05 - 버전 나이에 따른 계층적 보존 간격 결정
    // Nextcloud 보존 전략: 오래된 버전일수록 넓은 간격으로 솎아냄
    // 매개변수: versionAge - 현재 시각 기준 버전의 나이 (초)
    // 반환값: 해당 나이 구간에서 보존 간격으로 사용할 값 (초)
    int64_t getRequiredInterval(int64_t versionAge) {
        // {나이 임계값, 해당 구간의 보존 간격}
        // 예: 나이 < 10초 → 2초 간격으로 보존 (촘촘히)
        //     나이 < 60초 → 10초 간격으로 보존 (조금 솎아냄)
        //     ...
        //     나이 >= 1주  → 1주 간격으로 보존 (최소만)
        static const std::vector<std::pair<int64_t, int64_t>> tiers = {
            {10,     2},        // 10초 미만  → 2초 간격
            {60,     10},       // 1분 미만   → 10초 간격
            {3600,   60},       // 1시간 미만 → 1분 간격
            {86400,  3600},     // 1일 미만   → 1시간 간격
            {604800, 86400},    // 1주 미만   → 1일 간격
        };

        for (const auto& [threshold, interval] : tiers) {
            if (versionAge < threshold) {
                return interval;
            }
        }
        return 604800;  // 1주 이상 → 1주 간격
    }

    // 03/13 - JSON 문자열 이스케이프 헬퍼
    // 문제: userId 등에 ", \, 제어문자가 포함되면 JSON이 깨짐
    //       예: userId = "O\"Brien" → {"author":"O"Brien"} (파싱 실패)
    // 해결: JSON 스펙(RFC 8259)에 따라 특수문자를 이스케이프
    // 참고: JSON 라이브러리(nlohmann/json 등) 도입 시 이 함수는 대체됨
    std::string escapeJsonString(const std::string& input) {
        std::string output;
        output.reserve(input.size() + 8);  // 대부분 이스케이프 없음, 약간의 여유
        for (char c : input) {
            switch (c) {
                case '"':  output += "\\\""; break;
                case '\\': output += "\\\\"; break;
                case '\b': output += "\\b";  break;
                case '\f': output += "\\f";  break;
                case '\n': output += "\\n";  break;
                case '\r': output += "\\r";  break;
                case '\t': output += "\\t";  break;
                default:
                    // 제어문자(0x00~0x1F)는 \uXXXX로 이스케이프
                    if (static_cast<unsigned char>(c) < 0x20) {
                        char buf[8];
                        std::snprintf(buf, sizeof(buf), "\\u%04x",
                                      static_cast<unsigned int>(static_cast<unsigned char>(c)));
                        output += buf;
                    } else {
                        output += c;
                    }
            }
        }
        return output;
    }

    // 03/13 - 버전 메타데이터 JSON 생성 헬퍼
    // 현재는 author 필드만 포함, DLP 연동 시 추가 필드 확장 예정
    // JSON 라이브러리 도입 전까지 escapeJsonString으로 안전하게 조립
    std::string buildVersionMetadataJson(const std::string& userId) {
        return "{\"author\":\"" + escapeJsonString(userId) + "\"}";
    }

    std::map<std::string, std::string> parseJson(const std::string& json) {
        // JSON 문자열 파싱
        std::map<std::string, std::string> result;
        // 실제 구현에서는 JSON 파서 사용
        return result;
    }
};