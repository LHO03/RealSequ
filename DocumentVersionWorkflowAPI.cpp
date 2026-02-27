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

// 데이터 구조체 정의

// 파일 콘텐츠 및 메타데이터
struct FileContent {
    std::vector<uint8_t> data;
    std::string mimeType;
    size_t size; // 원래 동환님의 size 변수 추후에 정확한 의도 파악 후 방식 결정 요망
    // size_t size() const { return data.size(); } claude가 추천한 메서드 변환 방식
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
    int oldLineNumber;  // 원본에서의 줄 번호 (-1이면 해당 없음, 즉 ADDED인 경우)
    int newLineNumber;  // 수정본에서의 줄 번호 (-1이면 해당 없음, 즉 DELETED인 경우)
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
    bool isBinary;                  // 바이너리 파일 여부 (true면 해시 비교만 수행)
    std::string oldHash;            // 원본 SHA-256 해시 (간이 해시)
    std::string newHash;            // 수정본 SHA-256 해시 (간이 해시)
    int addedLines;                 // 추가된 줄 수 합계
    int deletedLines;               // 삭제된 줄 수 합계
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
// 역할 미정의
class VersionService {};

// Activity 및 Admin Audit 기록
class AuditLogService { 
public: 
    void logActivity(std::string u, std::string f, std::string a, std::string m) {} 
};

// 역할 미정의
class DocumentStatusManager {};

// 이벤트 디스패치 및 룰 평가
class WorkflowEngine { 
public: 
    void dispatchEvent(std::string e, std::map<std::string, std::string> d) {} 
    void evaluateRules(std::string e, std::map<std::string, std::string> d) {} 
};

// 푸시, 이메일, 웹 알림 발송
class NotificationService { 
public: 
    void notifyFileSubscribers(std::string f, std::string m, std::string u) {} 
    void sendNotification(std::string u, std::string s, std::string m, std::vector<std::string> c) {} 
};

// 버전 보존 정책 적용
class PolicyManager { 
public: 
    void applyRetentionPolicy(std::string f) {} 
};

// 파일 읽기, 쓰기, 복사, 삭제
class FileStorage {
public: 
    std::string generateFileId(std::string p) { return "file_id_" + p; }    // 단순한 문자열 연결이므로 구현되어 있지만 DB 연결을 통해 실제 DB기반 ID 생성으로 보완을 해야한다.
    void writeFile(std::string id, FileContent c) {}
    void copyFile(std::string src, std::string dst) {}
    FileContent readFile(std::string id) { return FileContent(); }
    void deleteFile(std::string path) {}
};

// DB 쿼리 실행 및 결과 조회
class DatabaseConnection {
public:
    // 편의상 모든 인자를 문자열로 변환하여 받는다고 가정
    void execute(std::string query, std::vector<std::string> params) {}
    std::vector<std::map<std::string, std::string>> query(std::string q, std::vector<std::string> params) { return {}; }
};

// ============================================
// DiffService 클래스
// 02/11
// ============================================
// 두 파일 버전 간의 차이를 서버 측에서 계산하는 서비스
// GitHub 스타일의 unified diff 형식으로 결과를 제공

// 설계원칙:
// - 텍스트 파일: LCS 기반 줄 단위 diff -> 상세 변겨 내역 제공
// - 바이너리 파일: SHA-256 해시 비교 -> 변경 여부만 판별
// - 결과는 DiffResult 구조체로 반환, DB 저장 및 로그에 활용
class DiffService {
public:
    // ==========================================
    // computeDiff: 핵심 진입점 - 두 FileContent 간의 diff를 계산
    // ==========================================
    // 매개변수:
    //  oldContent - 이전 버전의 파일 콘텐츠
    //  newContent - 새 버전의 파일 콘텐츠
    
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
        version.size = content.size;
        version.mimeType = content.mimeType;

        // DB: files_versions 테이블에 INSERT
        // 인덱스 (file_id, timestamp)
        // C++에서는 Initializer List 내 타입이 동일해야 하므로 문자열로 변환하여 전달
        // 02/10 - user_id 컬럼 추가, metadata에 author 저장
        std::string metadata = "{\"author\":\"" + userId + "\"}";
        db->execute("INSERT INTO files_versions (file_id, user_id, timestamp, size, mimetype, metadata) "
                    "VALUES (?, ?, ?, ?, ?, ?)",
                    {fileId, userId, std::to_string(timestamp), std::to_string(version.size), version.mimeType, metadata}); // 나중에 getVersionAtTime()에서 metadata["author"]를 찾는데 여기서 저장을 안 함

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
        version.size = currentContent.size;
        version.mimeType = currentContent.mimeType;

        // 02/10 - user_id 컬럼 추가, metadata에 author 저장
        std::string metadata = "{\"author\":\"" + userId + "\"}";
        db->execute("INSERT INTO files_versions (file_id, user_id, timestamp, size, mimetype, metadata) "
                    "VALUES (?, ?, ?, ?, ?, ?)",
                    {fileId, userId, std::to_string(timestamp), std::to_string(version.size), version.mimeType, metadata}); // 9.1과 마찬가지로 userId가 DB에 저장이 되지 않는다

        // 6. 버전 생성 완료 이벤트 (maps to VersionCreatedEvent)
        workflowEngine->dispatchEvent("version_created", {{"fileId", fileId}, {"versionId", versionId}});

        // 7. 활동 로그
        // 02/10 - 변경 내용을 포함한 로그 메시지 
        /* 그런데 이거는 다시 생각을 해봐야 할 점... 
        현재 사이즈를 비교해서 변경 내용을 기록하고 있는데 그렇다면 운이 좋아서 변경을 해도 사이즈가 바뀌지 않는다면 이것은 따로 변경을 하지 않은 것처럼 보임
        즉, 공격이 들어와서 내용을 변경하더라도 기록이 이렇게 남으면 알아차리기 힘들다...
        github와 같이 어떤 내용이 수정이 되었는 지를 기록할 수 있는 방법이 없을 지를 다시 생각해보자 
        */
        std::string logMsg = "Modified: size " + std::to_string(currentContent.size) +
                            " -> " + std::to_string(newContent.size) + " bytes";
        auditLog->logActivity(userId, fileId, "file_modified", logMsg); // 9.3에서 변경 내용을 요구하는데 여기서 아무 정보도 기록하지 않음

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
    // 서버는 diff 계산을 하지 않음. 클라이언트가 두 버전을 받아서 비교
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
            // 현재 버전
            diff.content1 = fileStorage->readFile(fileId);
        } else {
            // 과거 버전
            diff.content1 = fileStorage->readFile(versionPath1);
        }

        // 2. 두 번째 버전 콘텐츠 조회
        std::string versionPath2 = "files_versions/" + versionId2;
        if (versionId2 == "current") {
            diff.content2 = fileStorage->readFile(fileId);
        } else {
            diff.content2 = fileStorage->readFile(versionPath2);
        }

        // 3. 버전 메타데이터 조회 (maps to Storage::getVersions)
        // 02/10 - "currnent" versionId 처리 - extractTimestamp 호출 전 분기
        std::vector<std::map<std::string, std::string>> versionInfo1, versionInfo2;

        if (versionId1 == "current") {
            versionInfo1 = db->query("SELECT * FROM files_versions WHERE file_id = ? "
                                    "ORDER BY timestamp DESC LIMIT 1", {fileId});
        } else {
            auto ts1 = extractTimestamp(versionId1);
            if (!ts1.empty()) {
                versionInfo1 = db->query("SELECT * FROM files_versions WHERE file_id = ? "
                                        "AND timestamp = ?", {fileId, ts1});
            }
        }

        if (versionId2 == "current") {
            versionInfo2 = db->query("SELECT * FROM files_versions WHERE file_id = ? "
                                    "ORDER BY timestamp DESC LIMIT 1", {fileId});
        } else {
            auto ts2 = extractTimestamp(versionId2);
            if (!ts2.empty()) {
                versionInfo2 = db->query("SELECT * FROM files_versions WHERE file_id = ? "
                                        "AND timestamp = ?", {fileId, ts2});
            }
        }
        // auto versionInfo1 = db->query("SELECT * FROM files_versions WHERE file_id = ? "
        //                             "AND timestamp = ?", {fileId, extractTimestamp(versionId1)});
        // auto versionInfo2 = db->query("SELECT * FROM files_versions WHERE file_id = ? "
        //                             "AND timestamp = ?", {fileId, extractTimestamp(versionId2)});

        // 실제 diff 계산은 클라이언트 또는 별도 서비스에서 수행
        // 서버는 두 버전의 콘텐츠만 제공

        // 4. 활동 로그
        auditLog->logActivity(userId, fileId, "version_compared", versionId1 + " vs " + versionId2);

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
        std::string tagName;
        switch (status) {
            case DocumentStatus::DRAFT: tagName = "draft"; break;
            case DocumentStatus::UNDER_REVIEW: tagName = "under_review"; break;
            case DocumentStatus::APPROVED: tagName = "approved"; break;
            case DocumentStatus::REJECTED: tagName = "rejected"; break;
            case DocumentStatus::DEPRECATED: tagName = "deprecated"; break;
            // 02/10 - default case 추가 - 잘못된 enum 값 방어를 위함
            default:
                throw std::invalid_argument("Unknown DocumentStatus: " + std::to_string(static_cast<int>(status)));
        }

        // 2. 태그가 존재하는지 확인, 없으면 생성 (maps to ISystemTagManager::createTag)
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

        // 3. 기존 상태 태그 제거 (한 파일은 하나의 상태만 가질 수 있도록)
        std::vector<std::string> statusTags = {"draft", "under_review", "approved", "rejected", "deprecated"};
        for (const auto& oldTag : statusTags) {
            if (oldTag != tagName) {
                db->execute("DELETE FROM systemtag_object_mapping "
                            "WHERE objectid = ? AND objecttype = 'files' "
                            "AND systemtagid IN (SELECT id FROM systemtag WHERE name = ?)",
                            {fileId, oldTag});
            }
        }

        // 4. 새 상태 태그 할당 (maps to ISystemTagObjectMapper::assignTags)
        db->execute("INSERT OR REPLACE INTO systemtag_object_mapping "
                    "(objectid, objecttype, systemtagid) "
                    "VALUES (?, 'files', ?)", {fileId, tagId});

        // 5. 상태 변경 이력 기록
        logDocumentChangeHistory(userId, fileId, "status_changed", "Changed to " + tagName + ": " + comment);

        // 6. 워크플로우 트리거 (maps to WorkflowEngine check)
        // 태그 변경이 워크플로우 조건으로 사용될 수 있음
        workflowEngine->evaluateRules("tag_assigned", {
            {"fileId", fileId},
            {"tagName", tagName},
            {"userId", userId}
        });

        // 7. 알림 발송 (상태 변경 알림)
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
                // 1. pending 태그 할당 (승인 요청 시작)
                setDocumentStatus(userId, fileId, DocumentStatus::UNDER_REVIEW,
                                "Approval requested: " + comment);

                // 2. 승인 규칙 생성/확인 (maps to RuleService::createRule)
                // 02/10 - file_id를 규칙에 연결하여 파일별 승인 관리
                std::string ruleId = generateUUID();
                db->execute("INSERT INTO approval_rules (id, file_id, tag_pending, tag_approved, tag_rejected) "
                            "VALUES (?, ?, 'under_review', 'approved', 'rejected')", {ruleId, fileId});

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
                // 1. 승인 권한 확인
                auto approverCheck = db->query(
                    "SELECT rule_id FROM approval_rule_approvers "
                    "WHERE entity_id = ? AND rule_id IN "
                    "(SELECT id FROM approval_rules WHERE tag_pending = 'under_review' AND file_id = ?)",
                    {userId, fileId}
                );

                if (!approverCheck.empty()) {
                    // 2. approved 태그로 변경
                    setDocumentStatus(userId, fileId, DocumentStatus::APPROVED,
                                    "Approved: " + comment);

                    // 3. 승인 액션 기록 (maps to RuleService::storeAction)
                    // 02/10 - 초 단위로 통일
                    db->execute("INSERT INTO approval_activity (rule_id, user_id, action, "
                                "timestamp, comment) VALUES (?, ?, 'approved', ?, ?)",
                                {approverCheck[0]["rule_id"], userId, 
                                std::to_string(std::chrono::duration_cast<std::chrono::seconds>(
                                    std::chrono::system_clock::now().time_since_epoch()).count()),  
                                comment});

                    // 4. 요청자에게 알림
                    auto requester = db->query("SELECT entity_id FROM approval_rule_requesters WHERE rule_id = ?", {approverCheck[0]["rule_id"]});
                    if (!requester.empty()) {
                        notificationService->sendNotification(requester[0]["entity_id"],
                            "Your document was approved",
                            "File " + fileId + " has been approved by " + userId,
                            {"push", "email", "web"});
                    }

                    // 5. 다음 워크플로우 단계 트리거 (체인 워크플로우)
                    workflowEngine->evaluateRules("document_approved", {
                        {"fileId", fileId},
                        {"approverId", userId}
                    });

                    success = true;
                }
                break;
            }

            case ApprovalAction::REJECT: {
                // 승인과 유사하지만 rejected 태그 사용
                // 02/10 - case: APPROVE와 동일한 수준의 권한 검증 (file_id + tag_pending 조건)
                auto approverCheck = db->query(
                    "SELECT rule_id FROM approval_rule_approvers "
                    "WHERE entity_id = ? AND rule_id IN "
                    "(SELECT id FROM approval_rules WHERE tag_pending = 'under_review' AND file_id = ?)", 
                    {userId, fileId}
                );

                if (!approverCheck.empty()) {
                    // rejected 태그로 변경
                    // 02/10 - setDocumentStatus 사용 <- 태그 정리, 이력 기록, 워크플로우 트리거 통합
                    setDocumentStatus(userId, fileId, DocumentStatus::REJECTED, "Rejected: " + comment);

                    // 거부 액션 기록
                    db->execute("INSERT INTO approval_activity (rule_id, user_id, action, "
                                "timestamp, comment) VALUES (?, ?, 'rejected', ?, ?)",
                                {approverCheck[0]["rule_id"], userId, 
                                std::to_string(std::chrono::duration_cast<std::chrono::seconds>(
                                    std::chrono::system_clock::now().time_since_epoch()).count()), 
                                comment});

                    // 요청자에게 알림
                    // 02/10 - 요청자에게 알림 구현
                    auto requester = db->query("SELECT entity_id FROM approval_rule_requesters WHERE rule_id = ?",
                                                {approverCheck[0]["rule_id"]});
                    if (!requester.empty()) {
                        notificationService->sendNotification(requester[0]["entity_id"],
                            "Your document was rejected",
                            "File " + fileId + " has been rejected by " + userId + ". Comment: " + comment,
                            {"push", "email", "web"});
                    }
                    success = true;
                }
                break;
            }
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
        std::vector<std::string> toDelete;
        // 02/10 - 초 단위로 통일
        auto now = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();

        // 계층적 보존 전략 구현
        // (2초 → 10초 → 분 → 시간 → 일 → 주)
        std::vector<int64_t> retentionIntervals = {
            2, 10, 60, 3600, 86400, 604800  // 초 단위
        };

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
                toDelete.push_back(version.at("file_id") + ".v" + version.at("timestamp"));
                continue;
            }

            // 최대 보관 기간 체크
            // 02/10 - 초 단위 통일 - 86400초 = 1일
            if (policy.maxDays > 0 && versionAge > (int64_t)policy.maxDays * 86400) { // 단위 조정 가정
                toDelete.push_back(version.at("file_id") + ".v" + version.at("timestamp"));
                continue;
            }

            // 최소 보관 기간 내의 버전은 보존
            // 02/10 - 초 단위 통일
            if (policy.minDays > 0 && versionAge < (int64_t)policy.minDays * 86400) { // 단위 조정 가정
                lastKeptTimestamp = vTimestamp;
                continue;
            }

            // 계층적 간격 체크
            bool shouldKeep = false;
            for (const auto& interval : retentionIntervals) {
                // interval * 1000000은 마이크로초 단위 가정을 유지
                // 02/10 - 초 단위 통일
                if (lastKeptTimestamp - vTimestamp >= interval) {
                    shouldKeep = true;
                    lastKeptTimestamp = vTimestamp;
                    break;
                }
            }

            if (!shouldKeep && policy.autoCleanup) {
                toDelete.push_back(version.at("file_id") + ".v" + version.at("timestamp"));
            }
        }

        // 4. 할당량 기반 추가 정리 (maps to quota-based cleanup)
        if (policy.autoCleanup) {
            size_t totalSize = 0;
            for (const auto& version : versions) {
                totalSize += std::stoull(version.at("size"));
            }

            size_t quotaLimit = getQuotaLimit();
            // 02/10 - 음... 일단 낼 물어보자...
            if (totalSize > quotaLimit) {
                // 02/10 - size_t underflow 방지: 3개 미만이면 정리 대상 없음
                if (versions.size() >= 3) {
                    // int 캐스팅으로 unsigned underflow 방지
                    for (int i = static_cast<int>(versions.size()) - 1; i >= 2 && totalSize > quotaLimit; i--) {
                        auto versionId = versions[i].at("file_id") + ".v" + versions[i].at("timestamp");
                        if (std::find(toDelete.begin(), toDelete.end(), versionId) == toDelete.end()) {
                            toDelete.push_back(versionId);
                            totalSize -= std::stoull(versions[i].at("size"));
                        }
                    } // end for
                } // end of versionsSize >= 3 check
            } // end policy.autoCleanup

            // 기존의 방식
            // if (totalSize > quotaLimit) {
            //     // 오래된 버전부터 추가로 삭제 (최근 2개는 제외)
            //     for (size_t i = versions.size() - 1; i >= 2 && totalSize > quotaLimit; i--) {
            //         auto versionId = versions[i].at("file_id") + ".v" + versions[i].at("timestamp");
            //         if (std::find(toDelete.begin(), toDelete.end(), versionId) == toDelete.end()) {
            //             toDelete.push_back(versionId);
            //             totalSize -= std::stoull(versions[i].at("size"));
            //         }
            //     }
            // }
        }

        // 5. 버전 삭제 실행 (maps to Storage::expire)
        for (const auto& versionId : toDelete) {
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

    std::map<std::string, std::string> parseJson(const std::string& json) {
        // JSON 문자열 파싱
        std::map<std::string, std::string> result;
        // 실제 구현에서는 JSON 파서 사용
        return result;
    }
};