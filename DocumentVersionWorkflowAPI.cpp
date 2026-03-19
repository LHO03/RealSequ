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


// 03/18 - DiffService 관련 코드를 헤더 파일로 분리
// DiffLineType, DiffLine, DiffHunk, DiffMethod, DiffResult,
// DocumentType, DocumentTextExtractor, DiffService 클래스가 포함됨
// 주의: FileContent 구조체가 위에 정의된 후에 include해야 함
#include "DiffService.h"

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

// 03/18 - 알림 채널 enum 전환 (GPT 리뷰 반영)
//   변경 전: std::vector<std::string> + "push"/"email"/"web" 문자열 비교
//   문제: 오타를 컴파일러가 못 잡음, 타입 안정성 없음
//   변경 후: enum class NotificationChannel + switch 분기
enum class NotificationChannel {
    PUSH,
    EMAIL,
    WEB
};

struct NotificationTarget {
    std::string userId;
    std::vector<NotificationChannel> channels;
};

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
                          const std::string& m, const std::vector<NotificationChannel>& c) {} 
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

        // 3. 고유 버전 ID 생성 (maps to Storage::getVersionNameForFile)
        // 02/10 - timestamp 문제: 초 단위로 통일
        // 03/18 - 버전 ID 충돌 방지: 초단위 timestamp만으로는 1초 내 중복 저장 시 충돌
        //   수정: timestamp + monotonic counter로 고유성 보장
        //   예: "file_001.v1710700000_0", "file_001.v1710700000_1" (같은 초에 생성되어도 구분)
        auto timestamp = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();
        auto versionId = fileId + ".v" + std::to_string(timestamp) + "_" + std::to_string(versionCounter++);

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
        // 03/18 - MariaDB 호환: timestamp는 예약어이므로 백틱 필요
        // 03/18 - version_id 컬럼 추가: 초단위 timestamp 충돌 방지 (counter 포함 고유 ID)
        db->execute("INSERT INTO files_versions (version_id, file_id, user_id, `timestamp`, size, mimetype, metadata) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    {versionId, fileId, userId, std::to_string(timestamp), std::to_string(version.size), version.mimeType, metadata});

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
        // 03/18 - 버전 ID 충돌 방지: timestamp + counter
        auto timestamp = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();
        auto versionId = fileId + ".v" + std::to_string(timestamp) + "_" + std::to_string(versionCounter++);

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
        db->execute("INSERT INTO files_versions (version_id, file_id, user_id, `timestamp`, size, mimetype, metadata) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    {versionId, fileId, userId, std::to_string(timestamp), std::to_string(version.size), version.mimeType, metadata});

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
    // 03/18 - versionId 매개변수 추가 (GPT 리뷰 반영)
    //   변경 전: file_id + MAX(timestamp)로 최신 버전을 찾아 metadata 갱신
    //   문제: 같은 초에 여러 버전이 있으면 잘못된 버전까지 업데이트됨
    //   변경 후: versionId가 있을 때만 해당 버전의 metadata를 정확히 갱신
    //   호출부 호환: 기존 4인자 호출(setDocumentStatus 등)은 기본값 std::nullopt로 동작
    void logDocumentChangeHistory(const std::string& userId,
                                const std::string& fileId,
                                const std::string& action,
                                const std::string& reason = "",
                                const std::optional<std::string>& versionId = std::nullopt) {
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
        // 02/10 - message 필드 설정
        activity.message = action + (reason.empty() ? "" : " - Reason: " + reason);

        // reason 필드는 Nextcloud 기본 스키마에 없음
        // 커스텀 구현: metadata JSON 필드 또는 별도 테이블 필요
        if (!reason.empty()) {
            activity.reason = reason;
            // 03/18 - 특정 버전이 명확할 때만 version metadata 갱신
            //   versionId가 없는 호출(setDocumentStatus 등)은 activity/audit만 기록
            //   versionId가 있는 호출(버전 생성/수정)은 해당 버전의 metadata를 정확히 갱신
            if (versionId.has_value() && !versionId->empty()) {
                db->execute("UPDATE files_versions "
                            "SET metadata = JSON_SET(metadata, '$.reason', ?) "
                            "WHERE version_id = ?",
                            {reason, *versionId});
            }
        }

        // 2. Activity 테이블에 저장
        // 03/18 - MariaDB 호환: timestamp, user는 예약어이므로 백틱 필요
        db->execute("INSERT INTO activity (`timestamp`, `user`, affecteduser, app, subject, "
                    "subjectparams, file, object_type, object_id) "
                    "VALUES (?, ?, ?, 'files', ?, ?, ?, ?, ?)",
                    {std::to_string(activity.timestamp), activity.userId, activity.userId, activity.subject,
                    "{}", activity.objectId, activity.objectType, activity.objectId});

        // 3. Admin Audit 로그 (maps to Admin_Audit\Files)
        if (isAdminAuditEnabled()) {
            std::string auditLogMsg = "[" + std::to_string(activity.timestamp) + "] "
                                + "User: " + userId + ", Action: " + action
                                + ", File: " + fileId;
            if (!reason.empty()) {
                auditLogMsg += ", Reason: " + reason;
            }
            // 03/18 - versionId가 있으면 감사 로그에 포함
            if (versionId.has_value() && !versionId->empty()) {
                auditLogMsg += ", Version: " + *versionId;
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

        // 03/18 - limit 범위 방어 (GPT 리뷰 반영)
        //   변경 전: limit를 검증 없이 SQL 문자열에 직접 삽입
        //   문제: 음수나 과도한 값 시 쿼리 의미 이상 + 성능 문제
        //   변경 후: 1~100 범위로 clamp
        constexpr int kMinLimit = 1;
        constexpr int kMaxLimit = 100;
        if (limit < kMinLimit) limit = kMinLimit;
        else if (limit > kMaxLimit) limit = kMaxLimit;

        // 1. 모든 버전 목록 조회 (maps to Storage::getVersions)
        // 02/10 - LIMIT 값을 직접 삽입 (바인딩 파라미터 호환성 문제 방지)
        auto results = db->query(
            "SELECT version_id, file_id, user_id, `timestamp`, size, mimetype, metadata "
            "FROM files_versions "
            "WHERE file_id = ? AND `timestamp` <= ? "
            "ORDER BY `timestamp` DESC "
            "LIMIT " + std::to_string(limit), 
            {fileId, std::to_string(targetTimestamp)}
        );                      

        // 2. 각 버전 정보 구성 (maps to IVersion interface)
        for (const auto& row : results) {
            VersionInfo version;
            version.fileId = row.at("file_id");
            version.versionId = row.at("version_id");  // 03/18 - DB에서 직접 조회 (조합 제거)
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
                "SELECT version_id, file_id, user_id, `timestamp`, size, mimetype, metadata "
                "FROM files_versions "
                "WHERE file_id = ? AND `timestamp` > ? "
                "ORDER BY `timestamp` ASC LIMIT 1",
                {fileId, std::to_string(targetTimestamp)}
            );

            if (!futureResults.empty()) {
                // 위와 동일한 방식으로 VersionInfo 구성
                const auto& row = futureResults[0];
                VersionInfo version;
                version.fileId = row.at("file_id");
                version.versionId = row.at("version_id");  // 03/18 - DB에서 직접 조회
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
        // 03/18 - MariaDB 호환: INSERT OR REPLACE(SQLite 전용) → REPLACE INTO
        db->execute("REPLACE INTO systemtag_object_mapping "
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
                // 03/18 - setDocumentStatus 반환값 검사 추가
                //   배경: 03/05에 전이 검증이 추가되면서 실패 가능해졌으나 호출부 미업데이트
                //   문제: 전이 실패 시에도 approval_rules, approvers INSERT가 진행되어
                //         상태는 변경되지 않은 채 승인 규칙만 존재하는 불일치 발생
                //   수정: false 반환 시 로그 기록 후 break (success = false 유지)
                if (!setDocumentStatus(userId, fileId, DocumentStatus::UNDER_REVIEW,
                                "Approval requested: " + comment)) {
                    auditLog->logActivity(userId, fileId, "approval_failed",
                                        "Failed to transition to UNDER_REVIEW");
                    break;  // success = false 유지
                }

                // 2. 승인 규칙 생성/확인 (maps to RuleService::createRule)
                // 02/10 - file_id를 규칙에 연결하여 파일별 승인 관리
                // 03/05 - 태그 이름을 클래스 상수로 파라미터화
                // 03/18 - status 컬럼 추가: 승인 규칙 생명주기 관리
                //   문제: 승인/거절 후 rule을 종료하지 않아 오래된 규칙이 유효하게 남음
                //   수정: OPEN(진행 중) / CLOSED(완료) 상태로 관리
                std::string ruleId = generateUUID();
                db->execute("INSERT INTO approval_rules (id, file_id, tag_pending, tag_approved, tag_rejected, status) "
                            "VALUES (?, ?, ?, ?, ?, 'OPEN')", {ruleId, fileId, TAG_UNDER_REVIEW, TAG_APPROVED, TAG_REJECTED});

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
                        {NotificationChannel::PUSH, NotificationChannel::EMAIL, NotificationChannel::WEB});
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
            db->execute("INSERT INTO notifications (notification_id, app, `user`, `timestamp`,"
                        "object_type, object_id, subject, message) "
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        {notificationId, "files", target.userId, std::to_string(eventTimestamp),
                        "files", fileId, eventType, message});

            // 4. 채널별 전달 (maps to various delivery mechanisms)
            for (const auto& channel : target.channels) {
                // 03/18 - 문자열 비교 → enum switch (GPT 리뷰 반영)
                switch (channel) {
                    case NotificationChannel::PUSH: {
                        // 푸시 알림 (maps to Push::pushToDevice)
                        auto pushTokens = db->query("SELECT token FROM notifications_pushhash "
                                                    "WHERE uid = ?", {target.userId});
                        for (const auto& token : pushTokens) {
                            sendPushNotification(token.at("token"), message);
                        }
                        break;
                    }

                    case NotificationChannel::EMAIL: {
                        // 이메일 큐에 추가 (maps to mail queue)
                        db->execute("INSERT INTO notifications_mq (amq_timestamp, amq_affecteduser, "
                                    "amq_appid, amq_subject, amq_subjectparams) "
                                    "VALUES (?, ?, 'files', ?, ?)",
                                    {std::to_string(eventTimestamp), target.userId, eventType, message});
                        break;
                    }

                    case NotificationChannel::WEB:
                        // 웹 알림은 이미 notifications 테이블에 저장됨
                        // 클라이언트가 폴링하거나 WebSocket으로 수신
                        break;
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
        // 03/18 - version_id 컬럼 추가 조회 (삭제 시 고유 식별자로 사용)
        auto versions = db->query("SELECT version_id, file_id, `timestamp`, size FROM files_versions "
                                "WHERE file_id = ? ORDER BY `timestamp` DESC", {fileId});

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
                toDeleteSet.insert(version.at("version_id"));
                continue;
            }

            // 최대 보관 기간 체크
            // 02/10 - 초 단위 통일 - 86400초 = 1일
            if (policy.maxDays > 0 && versionAge > (int64_t)policy.maxDays * 86400) {
                toDeleteSet.insert(version.at("version_id"));
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
                toDeleteSet.insert(version.at("version_id"));
            }
        }

        // 4. 할당량 기반 추가 정리 (maps to quota-based cleanup)
        if (policy.autoCleanup) {
            // 03/18 - 과삭제(over-delete) 방지:
            //   변경 전: 전체 버전 size를 합산 → 이미 삭제 예정인 버전 용량이 포함되어
            //           quota 초과가 실제보다 크게 판단됨 → 불필요한 추가 삭제 발생
            //   변경 후: toDeleteSet에 이미 포함된 버전의 size를 제외한 실효 용량으로 판단
            size_t totalSize = 0;
            for (const auto& version : versions) {
                std::string vid = version.at("version_id");
                if (toDeleteSet.find(vid) == toDeleteSet.end()) {
                    // 보존 예정인 버전만 용량에 포함
                    totalSize += std::stoull(version.at("size"));
                }
            }

            size_t quotaLimit = getQuotaLimit();
            if (totalSize > quotaLimit) {
                // 02/10 - size_t underflow 방지: 3개 미만이면 정리 대상 없음
                if (versions.size() >= 3) {
                    // int 캐스팅으로 unsigned underflow 방지
                    for (int i = static_cast<int>(versions.size()) - 1; i >= 2 && totalSize > quotaLimit; i--) {
                        auto versionId = versions[i].at("version_id");  // 03/18 - DB에서 직접 조회
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
            // 03/18 - version_id 기반 삭제로 변경
            //   변경 전: file_id + timestamp로 삭제 → 같은 초에 생성된 버전이 2개면 모두 삭제됨
            //   변경 후: version_id(고유)로 삭제 → 정확히 해당 버전만 삭제
            db->execute("DELETE FROM files_versions WHERE version_id = ?",
                        {versionId});

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
    // 정적 카운터로 고유성 보장
    int uuidCounter = 0;
    int versionCounter = 0;  // 03/18 - 버전 ID 충돌 방지용 monotonic counter

    std::string generateUUID() {
        // UUID 생성 로직
        // 02/10 - timestamp + 카운터로 빠른 연속 생성에서도 고유성 보장
        return "uuid_" + std::to_string(std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count()) + "_" + std::to_string(uuidCounter++);
    }

    // 03/18 수정: versionId에서 타임스탬프 추출
    //   변경 전: find(".v") → fileId에 ".v"가 포함되면 잘못된 위치에서 파싱
    //   변경 후: rfind(".v") → 마지막 ".v"를 찾아 안전하게 파싱
    //   새 포맷: "fileId.v{timestamp}_{counter}" → "{timestamp}" 반환
    std::string extractTimestamp(const std::string& versionId) {
        auto pos = versionId.rfind(".v");  // 03/18: find → rfind (fileId에 .v 포함 시 안전)
        if (pos != std::string::npos) {
            std::string suffix = versionId.substr(pos + 2);  // "1710700000_0"
            // 03/18: counter 접미사("_0") 제거하여 순수 timestamp만 반환
            auto underscorePos = suffix.rfind('_');
            if (underscorePos != std::string::npos) {
                return suffix.substr(0, underscorePos);  // "1710700000"
            }
            return suffix;  // fallback: counter 없는 레거시 형식
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
        // 03/18 - status='OPEN' 조건 추가: 이미 처리 완료(CLOSED)된 규칙은 매칭하지 않음
        //   변경 전: tag_pending과 file_id만 확인 → 오래된 규칙이 계속 매칭됨
        //   변경 후: OPEN 상태인 규칙만 매칭 → stale rule 방지
        auto approverCheck = db->query(
            "SELECT rule_id FROM approval_rule_approvers "
            "WHERE entity_id = ? AND rule_id IN "
            "(SELECT id FROM approval_rules WHERE tag_pending = ? AND file_id = ? AND status = 'OPEN')",
            {userId, TAG_UNDER_REVIEW, fileId}
        );

        if (approverCheck.empty()) {
            return false;  // 권한 없음
        }

        std::string ruleId = approverCheck[0]["rule_id"];

        // 2. 상태 태그 변경
        // 03/18 - dead code 제거: actionVerb.substr(0,1) 대입 후 즉시 덮어쓰던 코드 정리
        // 03/18 - setDocumentStatus 반환값 검사 추가
        //   배경: 03/05에 isValidTransition이 추가되면서 setDocumentStatus가 false를
        //         반환할 수 있게 되었으나, 호출부가 이를 무시하여 상태 전이 실패 시에도
        //         승인 기록(approval_activity)과 알림이 진행되는 버그 발생
        //   수정: false 반환 시 로그 기록 후 즉시 실패 반환
        std::string statusComment = static_cast<char>(std::toupper(
            static_cast<unsigned char>(actionVerb[0])))
            + actionVerb.substr(1) + ": " + comment;

        if (!setDocumentStatus(userId, fileId, newStatus, statusComment)) {
            auditLog->logActivity(userId, fileId, "approval_" + actionVerb + "_failed",
                                "Status transition to " + std::string(actionTag) + " failed");
            return false;
        }

        // 3. 승인/거절 액션 기록 (maps to RuleService::storeAction)
        auto timestamp = std::to_string(std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()).count());
        db->execute("INSERT INTO approval_activity (rule_id, user_id, action, "
                    "`timestamp`, comment) VALUES (?, ?, ?, ?, ?)",
                    {ruleId, userId, actionTag, timestamp, comment});

        // 4. 요청자에게 알림
        auto requester = db->query(
            "SELECT entity_id FROM approval_rule_requesters WHERE rule_id = ?",
            {ruleId}
        );
        if (!requester.empty()) {
            std::string notifyBody = "File " + fileId + " has been " + actionVerb + " by " + userId;
            // 03/18 - 설계 검토 필요 (보고서 포함 대상)
            // 현재 동작: REJECT일 때만 comment를 알림 본문에 포함
            // 문제 인식: APPROVE 시에도 승인자가 comment를 남길 수 있으나
            //           (예: "조건부 승인: 2절 수정 후 배포 바람") 알림에 포함되지 않음
            // 원인 추정: 원래 APPROVE/REJECT가 별도 case였을 때 REJECT 쪽에만
            //           comment 처리가 있었고, 03/13 리팩토링에서 그대로 합쳐진 것
            // 명세서 확인: RD-SRS-9.7(승인 워크플로우), RD-SRS-9.9(알림)
            //             모두 알림 본문의 세부 필드까지는 정의하지 않음
            // 결정 필요:
            //   방안 A - APPROVE에도 comment 포함: 조건을 if (!comment.empty())로 변경
            //   방안 B - 현재 유지 (REJECT만): 거절 사유만 필수 전달, 승인 comment는 부가 정보
            // → 설계 단계에서 알림 본문 스펙 정의 시 확정 예정
            if (!comment.empty() && actionVerb == "rejected") {
                notifyBody += ". Comment: " + comment;
            }
            notificationService->sendNotification(requester[0]["entity_id"],
                notifySubject, notifyBody,
                {NotificationChannel::PUSH, NotificationChannel::EMAIL, NotificationChannel::WEB});
        }

        // 5. 승인 규칙 종료 (OPEN → CLOSED)
        // 03/18 - stale rule 방지: 승인/거절 완료 후 규칙을 CLOSED로 전환
        //   변경 전: 규칙이 영구적으로 OPEN 상태로 남아 동일 파일의
        //           이후 승인 요청에서 오래된 규칙이 매칭되는 문제
        //   변경 후: 처리 완료 시 CLOSED로 전환하여 재매칭 방지
        db->execute("UPDATE approval_rules SET status = 'CLOSED' WHERE id = ?",
                    {ruleId});

        return true;
    }

    // 03/05 - 파일의 현재 문서 상태를 태그 기반으로 조회
    // 반환: 현재 상태 태그 이름 (예: "draft", "approved")
    //       태그가 없으면 빈 문자열 (새 파일이거나 상태 미지정)
    // 03/18 - 5회 순회 쿼리 → 단일 JOIN 쿼리로 개선
    //   변경 전: 태그 5개를 for문으로 순회하며 매번 DB 쿼리 (최대 5회)
    //   변경 후: JOIN + IN 절로 1회 쿼리
    //   효과: setDocumentStatus 호출마다 최대 4회 불필요한 쿼리 제거
    //   참고: 실제 성능 차이는 DB 연동 후 체감 가능
    std::string getCurrentStatusTag(const std::string& fileId) {
        auto result = db->query(
            "SELECT st.name FROM systemtag_object_mapping som "
            "JOIN systemtag st ON som.systemtagid = st.id "
            "WHERE som.objectid = ? AND som.objecttype = 'files' "
            "AND st.name IN (?, ?, ?, ?, ?)",
            {fileId, TAG_DRAFT, TAG_UNDER_REVIEW, TAG_APPROVED, TAG_REJECTED, TAG_DEPRECATED}
        );
        return result.empty() ? "" : result[0].at("name");
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