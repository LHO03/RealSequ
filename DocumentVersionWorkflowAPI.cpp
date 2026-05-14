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
    REJECT,         // 승인 거절
    CANCEL          // 04/30 - Phase A-7: 승인 요청 취소 (요청자/승인자/관리자)
                    // REJECT와의 차이:
                    //   - REJECT: "검토했고 부적합" → 거절 사유와 함께 이력 보존
                    //   - CANCEL: "이 요청 자체가 무효, 처리 안 함" → 행정적 무효화
};

// 05/06 - Phase A-9: 다수 승인자 합의 모델 (결정 ① D 채택)
//   THRESHOLD : "M of N" 모델. 임계값(required_approvals) 도달 시 승인.
//               한 명이라도 임계값 도달 불가능한 거절 발생 시 즉시 거절.
//   UNANIMOUS : 만장일치. 전체 승인자가 모두 APPROVE해야 승인.
//               한 명이라도 REJECT면 즉시 거절.
//   SEQUENTIAL: 순차 승인. sequence_order 순서대로 진행.
//               앞 단계에서 REJECT 시 즉시 거절. 다음 단계는 앞 단계 완료 후만 가능.
// [Java 전환 시] 각 모드를 ConsensusStrategy 인터페이스 구현체로 분리 (Strategy 패턴)
enum class ApprovalConsensusMode {
    THRESHOLD,
    UNANIMOUS,
    SEQUENTIAL
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

// 05/06 - Phase A-10: 보존 정책 적용 범위 (결정 ① C 채택)
//   GLOBAL : 시스템 전체 기본값 (모든 파일)
//   USER   : 특정 사용자의 모든 파일
//   FOLDER : 특정 폴더 안의 파일들
//   FILE   : 특정 파일 단독
//   적용 우선순위 (결정 ②): FILE > FOLDER > USER > GLOBAL (가장 구체적인 것 하나만)
enum class RetentionPolicyScope {
    GLOBAL,
    USER,
    FOLDER,
    FILE
};

// 05/06 - Phase A-8 (결정 ⑥): 상태 전이 매트릭스 커스터마이징 설정
//   목적: 기본 매트릭스를 도입 기업 정책에 맞게 오버라이드 가능하도록 분리
//   동작: isValidTransition이 이 객체에서 매트릭스를 조회. 객체 미주입 시 기본값 사용.
//   확장 포인트: setTransitionMatrix() 호출로 런타임 변경 가능
// [Java 전환 시] application.yml에서 매트릭스 로드, @ConfigurationProperties로 주입.
//                또는 DB의 별도 테이블(state_transition_rules)에서 로드 가능
struct StateTransitionConfig {
    // 기본 매트릭스 (현재 시스템의 기본값)
    //   - "" → DRAFT, UNDER_REVIEW              새 파일 최초 상태 설정
    //   - DRAFT → UNDER_REVIEW, DEPRECATED      검토 요청 또는 폐기
    //   - UNDER_REVIEW → APPROVED, REJECTED, DRAFT
    //                                            승인/거절 결정 또는 CANCEL로 DRAFT 복귀
    //   - APPROVED → DEPRECATED                 승인 후 폐기만 일반 허용
    //                                            (DRAFT 복귀는 revertApprovedToDraft 별도 메서드)
    //   - REJECTED → DRAFT, DEPRECATED          수정 후 재제출 또는 포기
    //   - DEPRECATED → (전이 불가)              최종 상태
    //                                            (DRAFT 복원은 restoreFromDeprecated 별도 메서드)
    std::map<std::string, std::vector<std::string>> matrix;

    // 도입 기업이 매트릭스를 통째로 교체할 때 사용
    void setTransitionMatrix(const std::map<std::string, std::vector<std::string>>& m) {
        matrix = m;
    }

    // 비어있으면 기본 매트릭스로 간주 (런타임 lazy 초기화)
    bool isEmpty() const { return matrix.empty(); }
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
    // 04/30 - Phase A-2 후속: execute 반환 타입을 void → int로 변경
    //   변경 이유: unsubscribeFromFile에서 영향받은 row 수로 성공/실패를 판단해야 함
    //             (auto result = db->execute(...) 추론 실패 → 'auto'를 void로 추론하는 컴파일 에러)
    //   영향 범위: 기존 호출부는 모두 반환값을 무시하므로 변경 호환됨
    //   Java 전환 시: Spring JdbcTemplate.update()와 동일한 의미 (영향받은 row 수 반환)
    int execute(const std::string& query, const std::vector<std::string>& params) { return 0; }
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
    StateTransitionConfig* stateTransitionConfig = nullptr;  // 05/06 - Phase A-8: 전이 매트릭스 커스터마이징 (선택적 주입)

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

        // 04/30 - Phase A-3: 자동 트리거
        // [Java 전환 시] 이 메서드는 이벤트(VersionCreatedEvent)를 발행하고
        //                @EventListener가 notifyStakeholders를 호출하도록 분리
        notifyStakeholders(fileId, "version_created",
                           "Initial version created by " + userId, {});

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

        // 6. 버전 업데이트 완료 이벤트 (maps to VersionUpdatedEvent)
        // 05/14 - 이벤트 타입 수정: "version_created" → "version_updated"
        //   기존 문제: onDocumentModified는 의미상 버전 업데이트인데
        //              외부 워크플로우 엔진에는 "version_created"로 발행되어 의미 불일치
        //   영향: 외부 워크플로우 엔진이 두 이벤트(생성/업데이트)를 구분 처리 가능
        //   참조: 같은 메서드의 line 422 notifyStakeholders도 "version_updated" 사용
        workflowEngine->dispatchEvent("version_updated", {{"fileId", fileId}, {"versionId", versionId}});

        // 7. 활동 로그
        // 02/10 - 변경 내용을 포함한 로그 메시지
        // 03/13 - 사이즈 기반 로그 → diff summary로 교체
        // 기존 문제: 사이즈가 동일한 악의적 변경을 탐지 불가
        // 해결: DiffService로 실제 변경 내용(추가/삭제 줄 수)을 기록
        // 04/30 - Phase A-5: diff 결과를 version_diffs 테이블에 영속화
        std::string logMsg;
        if (diffService != nullptr) {
            DiffResult diffResult = diffService->computeDiff(currentContent, newContent);
            logMsg = "Modified: " + diffResult.summary;

            // 04/30 - version_diffs INSERT
            //   from = 방금 만든 versionId (수정 전 콘텐츠가 저장된 백업)
            //   to   = "current" (수정 후 콘텐츠 = 현재 파일)
            //   사용자 관점: "이번 수정이 무엇을 바꿨는가"의 답
            //   INSERT IGNORE: UNIQUE INDEX idx_version_pair 충돌 시 silent skip
            //                  (같은 (from, to) 페어가 이미 캐시되어 있으면 보존)
            // [Java 전환 시] hunks 필드를 Jackson으로 JSON 직렬화하여 저장.
            //                현재는 unifiedDiff 텍스트를 hunks_json 컬럼에 저장
            db->execute(
                "INSERT IGNORE INTO version_diffs "
                "(file_id, from_version_id, to_version_id, diff_method, "
                " added_lines, deleted_lines, summary, hunks_json, created_at) "
                "VALUES (?, ?, 'current', ?, ?, ?, ?, ?, ?)",
                {fileId, versionId, diffMethodToString(diffResult.method),
                 std::to_string(diffResult.addedLines),
                 std::to_string(diffResult.deletedLines),
                 diffResult.summary, diffResult.unifiedDiff,
                 std::to_string(timestamp)}
            );
        } else {
            // fallback: DiffService 미연결 시 기존 사이즈 기반 로그 유지
            logMsg = "Modified: size " + std::to_string(currentContent.size()) +
                    " -> " + std::to_string(newContent.size()) + " bytes";
        }
        auditLog->logActivity(userId, fileId, "file_modified", logMsg);

        // 8. 버전 정책 적용 (자동 정리)
        // 05/06 - Phase A-10: cascade로 적용 정책 결정 후 자동 정리
        //   기존: policyManager의 stub 호출 (실제 정리 안 됨)
        //   변경: evaluatePolicy로 정책 결정 → applyVersionRetentionPolicy로 정리
        // [Java 전환 시] @Async 처리로 응답 지연 최소화
        // 05/14 - 주석 보강: policyManager 스텁 호출은 호환성 유지용 무동작 호출
        //         실제 정리는 evaluatePolicy → applyVersionRetentionPolicy 경로에서 수행
        //         Java 전환 시 policyManager 스텁 호출은 제거 예정
        policyManager->applyRetentionPolicy(fileId);  // 호환성 유지용 stub (실제 동작 없음)
        RetentionPolicy effectivePolicy = evaluatePolicy(fileId);
        applyVersionRetentionPolicy(fileId, effectivePolicy);

        // 04/30 - Phase A-3: 자동 트리거
        // [Java 전환 시] VersionUpdatedEvent 발행으로 분리
        notifyStakeholders(fileId, "version_updated",
                           "New version: " + versionId + " by " + userId, {});

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
    // 04/30 - Phase A-5 헬퍼: DiffMethod enum → 문자열 변환
    //   version_diffs 테이블 저장용. Java 전환 시 enum.name() 대체
    static std::string diffMethodToString(DiffMethod method) {
        switch (method) {
            case DiffMethod::TEXT_DIRECT:    return "myers";
            case DiffMethod::TEXT_EXTRACTED: return "myers_extracted";
            case DiffMethod::HASH_ONLY:      return "sha256";
        }
        return "unknown";
    }

    // 05/14 - 헬퍼 추가: 문자열 → DiffMethod enum 역변환
    //   prepareVersionComparison의 캐시 hit 경로에서 사용
    //   알 수 없는 값(레거시, 손상된 데이터)은 안전 기본값 TEXT_DIRECT 반환
    static DiffMethod stringToDiffMethod(const std::string& s) {
        if (s == "myers")           return DiffMethod::TEXT_DIRECT;
        if (s == "myers_extracted") return DiffMethod::TEXT_EXTRACTED;
        if (s == "sha256")          return DiffMethod::HASH_ONLY;
        return DiffMethod::TEXT_DIRECT;  // 안전 기본값
    }

    // 04/30 - Phase A-5: 저장된 diff 캐시 직접 조회 (UI에서 호출)
    //   prepareVersionComparison 없이 캐시만 빠르게 조회하고 싶을 때 사용
    //   반환: 캐시 hit 시 hunks_json + 메타, miss 시 빈 문자열
    std::map<std::string, std::string> getVersionDiff(const std::string& fileId,
                                                       const std::string& fromVersionId,
                                                       const std::string& toVersionId) {
        auto rows = db->query(
            "SELECT diff_method, added_lines, deleted_lines, summary, hunks_json, created_at "
            "FROM version_diffs "
            "WHERE file_id = ? AND from_version_id = ? AND to_version_id = ? "
            "LIMIT 1",
            {fileId, fromVersionId, toVersionId}
        );
        if (rows.empty()) {
            return {};  // 캐시 miss
        }
        return rows[0];
    }

    // 03/13 - 기존: 서버는 콘텐츠만 제공, diff는 클라이언트 담당
    //         변경: DiffService를 통해 서버 측에서 diff 계산 후 결과를 포함하여 반환
    // 04/30 - Phase A-5: 캐시 우선 조회 (Q8=A 결정)
    //   동작: version_diffs 캐시 hit 시 즉시 반환, miss 시 계산 후 INSERT
    //   onDocumentModified가 매 수정마다 (versionId, "current") 페어를 INSERT하므로
    //   수정 직후의 비교는 대부분 캐시 hit. 과거 버전 간 비교는 첫 호출에서 계산됨.
    DiffInfo prepareVersionComparison(const std::string& userId,
                                    const std::string& fileId,
                                    const std::string& versionId1,
                                    const std::string& versionId2) {
        DiffInfo diff;
        diff.versionId1 = versionId1;
        diff.versionId2 = versionId2;

        // 0. 캐시 조회 (04/30 추가)
        auto cached = getVersionDiff(fileId, versionId1, versionId2);
        if (!cached.empty()) {
            // 캐시 hit: 콘텐츠는 옵션 (UI가 hunks_json만 필요로 할 수도 있음)
            //   여기서는 호환성을 위해 콘텐츠도 함께 로드. 향후 lazy loading 가능
            diff.diffResult.summary       = cached.at("summary");
            diff.diffResult.unifiedDiff   = cached.at("hunks_json");
            diff.diffResult.addedLines    = std::stoi(cached.at("added_lines"));
            diff.diffResult.deletedLines  = std::stoi(cached.at("deleted_lines"));
            // 05/14 - diff_method 복원 누락 수정
            //   캐시에 저장된 diff_method 문자열을 enum으로 역변환
            diff.diffResult.method        = stringToDiffMethod(cached.at("diff_method"));

            // 콘텐츠 로드 (캐시는 diff 결과만, 콘텐츠는 별도)
            diff.content1 = (versionId1 == "current")
                ? fileStorage->readFile(fileId)
                : fileStorage->readFile("files_versions/" + versionId1);
            diff.content2 = (versionId2 == "current")
                ? fileStorage->readFile(fileId)
                : fileStorage->readFile("files_versions/" + versionId2);

            auditLog->logActivity(userId, fileId, "version_compared",
                versionId1 + " vs " + versionId2 + " (cache hit)");
            return diff;
        }

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

            // 04/30 - Phase A-5: 계산 결과를 캐시에 저장
            auto now = std::chrono::duration_cast<std::chrono::seconds>(
                std::chrono::system_clock::now().time_since_epoch()
            ).count();
            db->execute(
                "INSERT IGNORE INTO version_diffs "
                "(file_id, from_version_id, to_version_id, diff_method, "
                " added_lines, deleted_lines, summary, hunks_json, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                {fileId, versionId1, versionId2,
                 diffMethodToString(diff.diffResult.method),
                 std::to_string(diff.diffResult.addedLines),
                 std::to_string(diff.diffResult.deletedLines),
                 diff.diffResult.summary, diff.diffResult.unifiedDiff,
                 std::to_string(now)}
            );
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
            logMsg += " (" + diff.diffResult.summary + ", computed)";
        }
        auditLog->logActivity(userId, fileId, "version_compared", logMsg);

        return diff;
    }

    // RD-SRS-9.5: 특정 시점의 문서 버전을 확인하고 조회할 수 있어야 함
    // code: apps/files_versions/lib/Storage.php (getVersions)
    //       apps/files_versions/lib/Versions/IVersion.php (getTimestamp)
    //       apps/files_versions/lib/Sabre/VersionCollection.php
    // Called from desktop via: PROPFIND /remote.php/dav/versions/{user}/versions/{fileId}/

    // 04/30 - Phase A-6: 페이지네이션 메타데이터용 전체 카운트
    //   클라이언트가 "12 / 248 페이지"를 표시할 때 필요
    //   getVersionsAtTime과 동일한 WHERE 조건 사용 (targetTimestamp 이하만)
    int64_t countVersions(const std::string& fileId, int64_t targetTimestamp) {
        auto rows = db->query(
            "SELECT COUNT(*) AS cnt FROM files_versions "
            "WHERE file_id = ? AND `timestamp` <= ?",
            {fileId, std::to_string(targetTimestamp)}
        );
        if (rows.empty()) return 0;
        return std::stoll(rows[0].at("cnt"));
    }

    std::vector<VersionInfo> getVersionsAtTime(const std::string& userId,
                                                const std::string& fileId,
                                                int64_t targetTimestamp,
                                                int limit = 50,
                                                int offset = 0) {
        std::vector<VersionInfo> versions;

        // 03/18 - limit 범위 방어 (GPT 리뷰 반영)
        //   변경 전: limit를 검증 없이 SQL 문자열에 직접 삽입
        //   문제: 음수나 과도한 값 시 쿼리 의미 이상 + 성능 문제
        //   변경 후: 1~100 범위로 clamp
        // 04/30 - Phase A-6 (Q10=A): 기본 limit 10 → 50으로 상향 (UI 페이지 사이즈)
        constexpr int kMinLimit = 1;
        constexpr int kMaxLimit = 100;
        if (limit < kMinLimit) limit = kMinLimit;
        else if (limit > kMaxLimit) limit = kMaxLimit;
        // 04/30 - offset 음수 방어
        if (offset < 0) offset = 0;

        // 1. 모든 버전 목록 조회 (maps to Storage::getVersions)
        // 02/10 - LIMIT 값을 직접 삽입 (바인딩 파라미터 호환성 문제 방지)
        // 04/30 - Phase A-6: OFFSET 추가 (페이지네이션 지원)
        // [Java 전환 시] cursor 기반 페이지네이션 검토.
        //                offset은 큰 페이지 번호에서 성능 저하 → cursor가 안전.
        //                의사코드 단계엔 표현 단순성 위해 offset 사용
        auto results = db->query(
            "SELECT version_id, file_id, user_id, `timestamp`, size, mimetype, metadata "
            "FROM files_versions "
            "WHERE file_id = ? AND `timestamp` <= ? "
            "ORDER BY `timestamp` DESC "
            "LIMIT " + std::to_string(limit) +
            " OFFSET " + std::to_string(offset),
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
        // 04/30 - Phase A-3: notifyFileSubscribers → notifyStakeholders 통합
        //   변경 전: APPROVED/DEPRECATED만 notifyFileSubscribers로 알림
        //   변경 후: 모든 상태 변경에 대해 notifyStakeholders 호출
        //          (구독자/이해관계자 결정은 notifyStakeholders에 위임)
        // [Java 전환 시] StatusChangedEvent 발행으로 분리
        notifyStakeholders(fileId, "status_changed",
                           "Document status changed to " + tagName +
                           (comment.empty() ? "" : ": " + comment),
                           {});

        return true;
    }

    // RD-SRS-9.7: 문서 승인 워크플로우 및 승인 프로세스 관리
    // code: apps/approval/lib/Service/RuleService.php (createRule, checkRule, storeAction)
    //       apps/workflowengine/lib/Manager.php
    //       apps/notifications (알림 발송)
    // Approval 앱은 별도 저장소이므로 기본 기능을 모방
    // 05/06 - Phase A-9: 합의 모드 + 임계값 매개변수 추가 (호환성 위해 기본값 제공)
    //   기존 호출(consensusMode/requiredApprovals 미지정): THRESHOLD 모드, 1명 승인
    //                                                   = 기존 "첫 승인자가 결정" 동작과 동일
    //   새 호출 시 SEQUENTIAL 모드면 approvers 순서가 sequence_order로 사용됨
    bool processApprovalWorkflow(const std::string& userId,
                                const std::string& fileId,
                                ApprovalAction action,
                                const std::string& comment = "",
                                const std::vector<std::string>& approvers = {},
                                ApprovalConsensusMode consensusMode = ApprovalConsensusMode::THRESHOLD,
                                int requiredApprovals = 1) {
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

                // 05/06 - Phase A-9: 임계값 정규화 (모드별 의미 통일)
                int effectiveRequired = requiredApprovals;
                if (consensusMode == ApprovalConsensusMode::UNANIMOUS) {
                    effectiveRequired = static_cast<int>(approvers.size());  // 만장일치 = 전체
                } else if (consensusMode == ApprovalConsensusMode::SEQUENTIAL) {
                    effectiveRequired = static_cast<int>(approvers.size());  // 순차 = 전체 통과
                } else {
                    // THRESHOLD: 1 ≤ required ≤ approvers.size()
                    if (effectiveRequired < 1) effectiveRequired = 1;
                    if (effectiveRequired > static_cast<int>(approvers.size())) {
                        effectiveRequired = static_cast<int>(approvers.size());
                    }
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

                // 2. 승인 규칙 생성 (05/06 Phase A-9: 합의 모드 컬럼 추가)
                // 02/10 - file_id를 규칙에 연결하여 파일별 승인 관리
                // 03/05 - 태그 이름을 클래스 상수로 파라미터화
                // 03/18 - status 컬럼 추가: 승인 규칙 생명주기 관리
                std::string ruleId = generateUUID();
                db->execute(
                    "INSERT INTO approval_rules "
                    "(id, file_id, tag_pending, tag_approved, tag_rejected, status, "
                    " consensus_mode, required_approvals, received_approvals, received_rejections) "
                    "VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?, 0, 0)",
                    {ruleId, fileId, TAG_UNDER_REVIEW, TAG_APPROVED, TAG_REJECTED,
                     consensusModeToString(consensusMode),
                     std::to_string(effectiveRequired)}
                );

                // 3. 요청자 등록 (maps to approval_rule_requesters)
                db->execute("INSERT INTO approval_rule_requesters (rule_id, entity_type, entity_id) "
                            "VALUES (?, 'user', ?)", {ruleId, userId});

                // 4. 승인자 등록 (05/06: SEQUENTIAL 모드는 sequence_order로 순서 부여)
                // 05/14 - 중복 승인자 방어 추가
                //   문제: approvers에 같은 ID가 중복되면 PK (rule_id, entity_type, entity_id) 충돌
                //         첫 INSERT는 성공, 두 번째부터 예외 → 부분 INSERT 상태로 종료
                //         (트랜잭션 없으므로 롤백 불가)
                //   해결: unordered_set으로 unique한 승인자만 INSERT, 입력 순서 유지
                //   SEQUENTIAL 영향: 중복 ID의 첫 등장 순서로 sequence_order 부여 (의도 부합)
                std::unordered_set<std::string> seenApprovers;
                int seqOrder = 1;
                for (const auto& approver : approvers) {
                    if (!seenApprovers.insert(approver).second) {
                        continue;  // 이미 INSERT한 승인자 (조용히 스킵)
                    }
                    int orderValue = (consensusMode == ApprovalConsensusMode::SEQUENTIAL)
                                     ? seqOrder : 0;
                    db->execute(
                        "INSERT INTO approval_rule_approvers "
                        "(rule_id, entity_type, entity_id, sequence_order) "
                        "VALUES (?, 'user', ?, ?)",
                        {ruleId, approver, std::to_string(orderValue)}
                    );
                    seqOrder++;
                }

                // 5. 승인자 + 이해관계자에게 알림 발송
                // 04/30 - Phase A-3: 호출부 전환 (Q3 결정 사항)
                //   변경 전: 승인자별 sendNotification 직접 호출 (for 루프 내)
                //   변경 후: notifyStakeholders 한 번 호출 → outbox 큐 통합 + dedup 적용
                //   승인자는 명시 targets로 전달 (구독자에 없을 수 있음)
                //   추가로 getDefaultStakeholders가 OPEN 규칙의 승인자/요청자도 포함
                // [Java 전환 시] ApprovalRequestedEvent 발행으로 분리
                std::vector<NotificationTarget> approverTargets;
                for (const auto& approver : approvers) {
                    approverTargets.push_back({approver,
                        {NotificationChannel::PUSH, NotificationChannel::EMAIL, NotificationChannel::WEB}});
                }
                notifyStakeholders(fileId, "approval_requested",
                    "User " + userId + " requested your approval. Comment: " + comment,
                    approverTargets);

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

            case ApprovalAction::CANCEL: {
                // 04/30 - Phase A-7: 승인 요청 취소 (Q11=B+C 결정)
                //   권한: 요청자 본인 + 관리자 + 승인자 (셋 중 하나)
                //   REJECT와의 차이: 거절 사유 기록 없음. 행정적 무효화 처리.
                //   상태 전이: UNDER_REVIEW → DRAFT (재작업 가능)
                //   approval_rules.status: OPEN → CANCELLED
                success = cancelApprovalRequest(userId, fileId, comment);
                break;
            }

            // 03/05 - default case 추가 (setDocumentStatus와 동일한 방어 패턴)
            default:
                throw std::invalid_argument("Unknown ApprovalAction: " + std::to_string(static_cast<int>(action)));
        }

        return success;
    }

    // 04/30 - Phase A-7: 승인 요청 취소 처리
    //   권한 (Q11=B+C 결정):
    //     1) 요청자 본인 — 본인이 요청한 것을 철회
    //     2) 관리자 — 시스템 관리자 (휴직/퇴사 사용자의 stale 요청 정리 등)
    //     3) 승인자 — "이 요청 자체가 부적절"이라 판단 시 무효화
    //   동작:
    //     1) approval_rules에서 OPEN 상태인 규칙 조회
    //     2) 권한 체크 (위 3가지 중 하나라도 해당하면 통과)
    //     3) approval_rules.status = 'CANCELLED'
    //     4) approval_activity에 action='cancelled' 기록
    //     5) 상태 전이: UNDER_REVIEW → DRAFT
    //     6) 이해관계자 알림 (자동 트리거)
    // [Java 전환 시] @PreAuthorize로 권한 체크 분리. ApprovalCancelledEvent 발행.
    bool cancelApprovalRequest(const std::string& userId,
                               const std::string& fileId,
                               const std::string& comment) {
        // 1. OPEN 상태 규칙 조회
        auto rules = db->query(
            "SELECT id FROM approval_rules "
            "WHERE file_id = ? AND status = 'OPEN' LIMIT 1",
            {fileId}
        );
        if (rules.empty()) {
            auditLog->logActivity(userId, fileId, "approval_cancel_failed",
                                  "No open approval request found");
            return false;
        }
        std::string ruleId = rules[0].at("id");

        // 2. 권한 체크 (B+C 결합)
        bool isRequester = false;
        auto reqCheck = db->query(
            "SELECT 1 FROM approval_rule_requesters "
            "WHERE rule_id = ? AND entity_id = ? LIMIT 1",
            {ruleId, userId}
        );
        if (!reqCheck.empty()) isRequester = true;

        bool isApprover = false;
        auto apprCheck = db->query(
            "SELECT 1 FROM approval_rule_approvers "
            "WHERE rule_id = ? AND entity_id = ? LIMIT 1",
            {ruleId, userId}
        );
        if (!apprCheck.empty()) isApprover = true;

        bool isAdminUser = isAdmin(userId);

        if (!isRequester && !isApprover && !isAdminUser) {
            auditLog->logActivity(userId, fileId, "approval_cancel_denied",
                                  "User has no permission to cancel rule " + ruleId);
            return false;
        }

        // 3. 규칙 상태 변경
        int affected = db->execute(
            "UPDATE approval_rules SET status = 'CANCELLED' "
            "WHERE id = ? AND status = 'OPEN'",
            {ruleId}
        );
        if (affected == 0) {
            // race condition: 다른 호출이 먼저 닫은 경우
            return false;
        }

        // 4. 활동 이력 기록 (cancelled action)
        auto now = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();
        std::string actorRole = isRequester ? "requester"
                              : isApprover  ? "approver"
                              : "admin";
        db->execute(
            "INSERT INTO approval_activity "
            "(rule_id, user_id, action, `timestamp`, comment) "
            "VALUES (?, ?, 'cancelled', ?, ?)",
            {ruleId, userId, std::to_string(now),
             "Cancelled by " + actorRole +
             (comment.empty() ? "" : ": " + comment)}
        );

        // 5. 상태 전이: UNDER_REVIEW → DRAFT
        // 03/18 - setDocumentStatus 반환값 검사 패턴 유지
        if (!setDocumentStatus(userId, fileId, DocumentStatus::DRAFT,
                               "Approval request cancelled")) {
            auditLog->logActivity(userId, fileId, "approval_cancel_partial",
                "Rule cancelled but status revert failed for rule " + ruleId);
            // rule은 이미 CANCELLED 상태이므로 false 반환은 부적절.
            // 부분 실패는 로그만 남기고 true 반환 (rule은 닫힌 상태)
        }

        // 6. 이해관계자에게 broadcast 알림 (Phase A-3 자동 트리거 패턴 재사용)
        notifyStakeholders(fileId, "approval_cancelled",
            "Approval request was cancelled by " + actorRole +
            " (" + userId + ")" +
            (comment.empty() ? "" : ". Reason: " + comment),
            {});

        return true;
    }

    // 04/30 - Phase A-7: 관리자 권한 체크
    // 05/06 - 의사코드 99% 보강: 스텁 → 실제 DB 조회로 전환
    //   Q2=A 결정: USER / ADMIN 두 역할만 사용
    //   향후 세분화 시 역할별 hasRole(userId, "EDITOR") 같은 일반 메서드 추가 가능
    // [Java 전환 시] Spring Security의 hasRole('ADMIN') 또는
    //                @PreAuthorize("hasAuthority('approval:cancel:any')")로 대체
    bool isAdmin(const std::string& userId) {
        if (userId.empty()) return false;
        auto rows = db->query(
            "SELECT 1 FROM user_roles WHERE user_id = ? AND role = 'ADMIN' LIMIT 1",
            {userId}
        );
        return !rows.empty();
    }

    // 05/06 - Phase ②: 역할 부여 (관리자만 다른 사용자에게 ADMIN 부여 가능)
    //   재귀 부여 방지: SUPER_ADMIN 같은 메타 권한이 추후 추가되면 검증 강화 필요
    bool assignRole(const std::string& granterId,
                    const std::string& targetUserId,
                    const std::string& role) {
        if (!isAdmin(granterId)) {
            auditLog->logActivity(granterId, "", "role_assign_denied",
                "Non-admin attempted to assign role " + role);
            return false;
        }
        if (targetUserId.empty() || role.empty()) return false;

        auto now = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();

        // INSERT IGNORE: 이미 같은 (user, role) 있으면 silent skip
        db->execute(
            "INSERT IGNORE INTO user_roles (user_id, role, granted_by, granted_at) "
            "VALUES (?, ?, ?, ?)",
            {targetUserId, role, granterId, std::to_string(now)}
        );
        auditLog->logActivity(granterId, "", "role_assigned",
            "User " + targetUserId + " granted role " + role);
        return true;
    }

    // 05/06 - Phase ②: 역할 회수
    //   05/14 - 마지막 ADMIN 회수 차단 추가
    //     문제: 시스템에 ADMIN이 1명일 때 본인 ADMIN을 회수하면
    //           assignRole/revokeRole 등 isAdmin이 필요한 모든 작업이 영구 불가 (lockout)
    //     해결: ADMIN 역할 회수 시 다른 ADMIN이 1명 이상 남는지 확인
    //   [Java 전환 시] 별도 정책 객체로 분리하여 도입 기업이 재정의 가능하게
    bool revokeRole(const std::string& granterId,
                    const std::string& targetUserId,
                    const std::string& role) {
        if (!isAdmin(granterId)) {
            auditLog->logActivity(granterId, "", "role_revoke_denied",
                "Non-admin attempted to revoke role");
            return false;
        }

        // 05/14 - 마지막 ADMIN 회수 차단
        if (role == "ADMIN") {
            auto adminCount = db->query(
                "SELECT COUNT(*) AS cnt FROM user_roles WHERE role = 'ADMIN'", {}
            );
            int total = adminCount.empty() ? 0 : std::stoi(adminCount[0].at("cnt"));
            if (total <= 1) {
                auditLog->logActivity(granterId, "", "role_revoke_denied",
                    "Cannot revoke last remaining ADMIN (lockout prevention)");
                return false;
            }
        }

        int affected = db->execute(
            "DELETE FROM user_roles WHERE user_id = ? AND role = ?",
            {targetUserId, role}
        );
        if (affected > 0) {
            auditLog->logActivity(granterId, "", "role_revoked",
                "User " + targetUserId + " revoked role " + role);
        }
        return affected > 0;
    }

    // 05/06 - Phase ②: 모든 ADMIN 사용자 목록 (관리 UI용)
    std::vector<std::string> listAdmins() {
        std::vector<std::string> result;
        auto rows = db->query(
            "SELECT user_id FROM user_roles WHERE role = 'ADMIN' ORDER BY granted_at",
            {}
        );
        for (const auto& row : rows) {
            result.push_back(row.at("user_id"));
        }
        return result;
    }

    // ============================================================
    // RD-SRS-9.7 승인 위임/대리 승인 (Phase ①, 05/06 추가)
    // ------------------------------------------------------------
    // 목적: 승인자가 휴가/출장 등으로 부재 시 다른 사용자에게 권한 임시 양도
    // 동작 (Q1=C 결정):
    //   - 임시 위임: expires_at = 만료 Unix timestamp
    //   - 영구 위임: expires_at = NULL (위임자가 명시적 회수까지 유효)
    // 권한 평가: processApprovalDecision이 위임 활성 여부 검사
    // ============================================================

    // 위임 생성
    //   delegatorId: 승인 권한을 가진 사용자 (위임자)
    //   delegateId : 권한을 받을 사용자 (피위임자)
    //   expiresAt  : 0 = 영구, 양수 = 만료 시각
    //   반환: 위임 ID (UUID), 실패 시 빈 문자열
    std::string createDelegation(const std::string& delegatorId,
                                  const std::string& delegateId,
                                  int64_t expiresAt,
                                  const std::string& reason) {
        if (delegatorId.empty() || delegateId.empty()) return "";
        if (delegatorId == delegateId) {
            auditLog->logActivity(delegatorId, "", "delegation_denied",
                                  "Cannot delegate to self");
            return "";
        }

        // expires_at 검증: 0이면 영구, 양수면 미래 시각이어야 함
        auto now = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();
        if (expiresAt != 0 && expiresAt <= now) {
            auditLog->logActivity(delegatorId, "", "delegation_denied",
                                  "expires_at must be 0 (permanent) or future timestamp");
            return "";
        }

        std::string delegationId = generateUUID();

        // expires_at NULL 처리: 0이면 NULL로 INSERT
        if (expiresAt == 0) {
            db->execute(
                "INSERT INTO approval_delegations "
                "(id, delegator_id, delegate_id, reason, created_at, expires_at) "
                "VALUES (?, ?, ?, ?, ?, NULL)",
                {delegationId, delegatorId, delegateId, reason, std::to_string(now)}
            );
        } else {
            db->execute(
                "INSERT INTO approval_delegations "
                "(id, delegator_id, delegate_id, reason, created_at, expires_at) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                {delegationId, delegatorId, delegateId, reason,
                 std::to_string(now), std::to_string(expiresAt)}
            );
        }

        std::string typeLabel = (expiresAt == 0) ? "permanent" : "temporary";
        auditLog->logActivity(delegatorId, "", "delegation_created",
            "Delegated to " + delegateId + " (" + typeLabel + "): " + reason);
        return delegationId;
    }

    // 위임 회수
    //   본인의 위임만 회수 가능 (관리자는 isAdmin 체크 후 모든 위임 회수 가능)
    bool revokeDelegation(const std::string& userId,
                          const std::string& delegationId) {
        // 위임 정보 조회
        auto rows = db->query(
            "SELECT delegator_id FROM approval_delegations WHERE id = ?",
            {delegationId}
        );
        if (rows.empty()) return false;

        std::string delegatorId = rows[0].at("delegator_id");
        bool canRevoke = (userId == delegatorId) || isAdmin(userId);
        if (!canRevoke) {
            auditLog->logActivity(userId, "", "delegation_revoke_denied",
                "Cannot revoke another user's delegation " + delegationId);
            return false;
        }

        int affected = db->execute(
            "DELETE FROM approval_delegations WHERE id = ?",
            {delegationId}
        );
        if (affected > 0) {
            auditLog->logActivity(userId, "", "delegation_revoked", delegationId);
        }
        return affected > 0;
    }

    // 활성 위임 목록 조회
    //   forDelegator=true : 이 사용자가 위임한 것들 (delegator_id 기준)
    //   forDelegator=false: 이 사용자에게 위임된 것들 (delegate_id 기준)
    std::vector<std::map<std::string, std::string>> getActiveDelegations(
            const std::string& userId, bool forDelegator) {
        auto now = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();

        std::string column = forDelegator ? "delegator_id" : "delegate_id";
        // 활성 조건: expires_at IS NULL (영구) OR expires_at > now (미만료)
        return db->query(
            "SELECT id, delegator_id, delegate_id, reason, created_at, expires_at "
            "FROM approval_delegations "
            "WHERE " + column + " = ? "
            "  AND (expires_at IS NULL OR expires_at > ?) "
            "ORDER BY created_at DESC",
            {userId, std::to_string(now)}
        );
    }

    // 본인이 누군가의 활성 피위임자인지 확인
    //   processApprovalDecision의 권한 체크에서 사용
    //   반환: 본인이 위임받은 위임자 user_id 목록 (여러 명 위임받았을 수 있음)
    std::vector<std::string> getActiveDelegatorsOf(const std::string& delegateUserId) {
        std::vector<std::string> result;
        auto now = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();

        auto rows = db->query(
            "SELECT DISTINCT delegator_id FROM approval_delegations "
            "WHERE delegate_id = ? "
            "  AND (expires_at IS NULL OR expires_at > ?)",
            {delegateUserId, std::to_string(now)}
        );
        for (const auto& row : rows) {
            result.push_back(row.at("delegator_id"));
        }
        return result;
    }

    // ============================================================
    // RD-SRS-9.6 특수 상태 복원 메서드 (Phase A-8, 05/06 추가)
    // ------------------------------------------------------------
    // 일반 매트릭스로 허용되지 않는 전이를 명시적 의도와 권한 체크로 우회
    // - DEPRECATED → DRAFT (관리자만): restoreFromDeprecated
    // - APPROVED → DRAFT (작성자/관리자, 사유 필수): revertApprovedToDraft
    // ============================================================

    // DEPRECATED → DRAFT 복원 (결정 ②)
    //   권한: 관리자만 (isAdmin)
    //   시나리오: 잘못 폐기된 문서 복원, 관리상 필요
    //   구현: 매트릭스를 우회하기 위해 setDocumentStatus를 직접 호출하지 않고
    //         systemtag_object_mapping을 직접 갱신 + 활동 로그 + 알림
    // [Java 전환 시] @PreAuthorize("hasRole('ADMIN')")로 권한 보장
    bool restoreFromDeprecated(const std::string& adminUserId,
                               const std::string& fileId,
                               const std::string& reason) {
        // 1. 권한 체크 (관리자만)
        if (!isAdmin(adminUserId)) {
            auditLog->logActivity(adminUserId, fileId, "restore_denied",
                                  "Non-admin attempted DEPRECATED → DRAFT restore");
            return false;
        }

        // 2. 사유 필수 (감사 추적)
        if (reason.empty()) {
            auditLog->logActivity(adminUserId, fileId, "restore_denied",
                                  "Reason is required for DEPRECATED restore");
            return false;
        }

        // 3. 현재 상태가 DEPRECATED인지 확인
        std::string currentTag = getCurrentStatusTag(fileId);
        if (currentTag != TAG_DEPRECATED) {
            auditLog->logActivity(adminUserId, fileId, "restore_denied",
                                  "File is not in DEPRECATED state (current: " + currentTag + ")");
            return false;
        }

        // 4. 태그 직접 갱신 (isValidTransition 우회)
        // 03/18 - MariaDB 호환: REPLACE INTO
        auto draftTagRows = db->query(
            "SELECT id FROM systemtag WHERE name = ?", {std::string(TAG_DRAFT)}
        );
        if (draftTagRows.empty()) {
            return false;  // 태그 미정의 (초기 데이터 누락)
        }
        std::string draftTagId = draftTagRows[0].at("id");

        db->execute(
            "REPLACE INTO systemtag_object_mapping "
            "(objectid, objecttype, systemtagid) "
            "VALUES (?, 'files', ?)",
            {fileId, draftTagId}
        );

        // 5. 활동 로그 + 변경 이력
        logDocumentChangeHistory(adminUserId, fileId, "status_restored",
            "DEPRECATED → DRAFT (admin restore). Reason: " + reason);

        // 6. 이해관계자 알림
        notifyStakeholders(fileId, "status_restored",
            "Document restored from DEPRECATED to DRAFT by admin. Reason: " + reason,
            {});

        return true;
    }

    // APPROVED → DRAFT 되돌리기 (결정 ③, 오류 수정 한정 허용)
    //   권한: 작성자(파일 소유자) 또는 관리자
    //   시나리오: 승인된 문서에 오타/오류 발견 → 수정 후 재승인 필요
    //   사유 필수: 향후 감사에서 "왜 승인을 무효화했는가" 추적 가능
    //   주의: 새 버전을 만드는 게 아니라 같은 문서의 상태를 되돌림.
    //        기존 approval_activity 이력은 그대로 보존되어 추적성 유지.
    // [Java 전환 시] @PreAuthorize로 (소유자 OR 관리자) 권한 분리
    bool revertApprovedToDraft(const std::string& userId,
                               const std::string& fileId,
                               const std::string& errorReason) {
        // 1. 권한 체크 (소유자 OR 관리자)
        bool isOwner = false;
        auto ownerRows = db->query(
            "SELECT user_id FROM files_versions "
            "WHERE file_id = ? ORDER BY `timestamp` ASC LIMIT 1",
            {fileId}
        );
        if (!ownerRows.empty() && ownerRows[0].at("user_id") == userId) {
            isOwner = true;
        }

        if (!isOwner && !isAdmin(userId)) {
            auditLog->logActivity(userId, fileId, "revert_denied",
                                  "User has no permission to revert approved document");
            return false;
        }

        // 2. 사유 필수
        if (errorReason.empty()) {
            auditLog->logActivity(userId, fileId, "revert_denied",
                                  "Error reason is required for APPROVED → DRAFT revert");
            return false;
        }

        // 3. 현재 상태가 APPROVED인지 확인
        std::string currentTag = getCurrentStatusTag(fileId);
        if (currentTag != TAG_APPROVED) {
            auditLog->logActivity(userId, fileId, "revert_denied",
                                  "File is not APPROVED (current: " + currentTag + ")");
            return false;
        }

        // 4. 태그 갱신 (isValidTransition 우회 — 의도된 예외)
        auto draftTagRows = db->query(
            "SELECT id FROM systemtag WHERE name = ?", {std::string(TAG_DRAFT)}
        );
        if (draftTagRows.empty()) return false;
        std::string draftTagId = draftTagRows[0].at("id");

        db->execute(
            "REPLACE INTO systemtag_object_mapping "
            "(objectid, objecttype, systemtagid) "
            "VALUES (?, 'files', ?)",
            {fileId, draftTagId}
        );

        // 5. 변경 이력 (감사 추적)
        std::string actor = isOwner ? "owner" : "admin";
        logDocumentChangeHistory(userId, fileId, "approved_reverted",
            "APPROVED → DRAFT by " + actor + " (error correction). Reason: " + errorReason);

        // 6. 이해관계자 알림
        notifyStakeholders(fileId, "approved_reverted",
            "Approved document reverted to DRAFT for error correction by " +
            actor + ": " + errorReason,
            {});

        return true;
    }

    // ============================================================
    // RD-SRS-9.9 구독 관리 (Phase A-2, 04/30 추가)
    // ------------------------------------------------------------
    // 목적: notifyStakeholders 호출 시 외부에서 targets를 직접 지정하던 방식을
    //      파일별 구독자 목록을 자동 조회하는 방식으로 전환
    // 구성:
    //   subscribeToFile        - 사용자가 파일 알림을 구독
    //   unsubscribeFromFile    - 구독 해제
    //   pauseSubscription      - 일정 기간 일시정지 (출장/휴가 등)
    //   getSubscribers         - 활성 구독자 목록 조회
    //   getDefaultStakeholders - 명시적 구독 외 자동 이해관계자 조회
    //                            (파일 소유자 + 마지막 수정자 + 진행 중 승인 관련자)
    // 연관 테이블: file_subscriptions, file_subscription_channels
    // [Java 전환 시] @Transactional로 구독+채널 INSERT 원자성 보장
    // ============================================================

    // 파일 구독 추가 또는 갱신
    // - 이미 구독 중이면 채널 설정만 갱신 (REPLACE 의미)
    // - channels가 비어있으면 PUSH+WEB 기본값 적용
    bool subscribeToFile(const std::string& userId,
                         const std::string& fileId,
                         const std::vector<NotificationChannel>& channels) {
        if (userId.empty() || fileId.empty()) {
            return false;
        }

        auto now = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();

        // 1. 구독 레코드 upsert (이미 있으면 created_at 유지를 위해 INSERT IGNORE)
        db->execute(
            "INSERT INTO file_subscriptions (file_id, user_id, created_at, paused_until) "
            "VALUES (?, ?, ?, NULL) "
            "ON DUPLICATE KEY UPDATE paused_until = NULL",
            {fileId, userId, std::to_string(now)}
        );

        // 2. 기존 채널 설정 모두 제거 후 신규 설정 삽입
        // [Java 전환 시] @Transactional 범위에 1~3단계 전체 포함
        db->execute(
            "DELETE FROM file_subscription_channels WHERE file_id = ? AND user_id = ?",
            {fileId, userId}
        );

        // 3. 채널 정규화: 빈 입력이면 기본값(PUSH+WEB) 사용
        // 05/14 - 중복 채널 dedup 추가 (PK file_id, user_id, channel 충돌 방어)
        std::vector<NotificationChannel> effective = channels;
        if (effective.empty()) {
            effective = {NotificationChannel::PUSH, NotificationChannel::WEB};
        }

        std::unordered_set<std::string> seenChannels;
        for (const auto& ch : effective) {
            std::string chStr = channelToString(ch);
            if (!seenChannels.insert(chStr).second) {
                continue;  // 이미 INSERT한 채널 (중복 입력 방어)
            }
            db->execute(
                "INSERT INTO file_subscription_channels (file_id, user_id, channel, enabled) "
                "VALUES (?, ?, ?, 1)",
                {fileId, userId, chStr}
            );
        }

        // 4. 활동 로그
        auditLog->logActivity(userId, fileId, "subscription_added",
                              "Channels: " + std::to_string(effective.size()));
        return true;
    }

    // 파일 구독 해제
    // - 채널 설정은 FK ON DELETE CASCADE로 함께 삭제됨
    // - 영향받은 row가 0이면 (구독이 없으면) false 반환
    bool unsubscribeFromFile(const std::string& userId, const std::string& fileId) {
        if (userId.empty() || fileId.empty()) {
            return false;
        }

        int affected = db->execute(
            "DELETE FROM file_subscriptions WHERE file_id = ? AND user_id = ?",
            {fileId, userId}
        );

        if (affected > 0) {
            auditLog->logActivity(userId, fileId, "subscription_removed", "");
        }
        return affected > 0;
    }

    // 구독 일시정지
    // - untilTimestamp가 0이면 일시정지 해제
    // - 일시정지 중에는 getSubscribers 결과에서 제외됨
    // - 영향받은 row가 0이면 (구독이 없으면) false 반환
    bool pauseSubscription(const std::string& userId,
                           const std::string& fileId,
                           int64_t untilTimestamp) {
        if (userId.empty() || fileId.empty()) {
            return false;
        }

        // NULL은 파라미터로 직접 못 보내므로 분기
        int affected;
        if (untilTimestamp > 0) {
            affected = db->execute(
                "UPDATE file_subscriptions SET paused_until = ? "
                "WHERE file_id = ? AND user_id = ?",
                {std::to_string(untilTimestamp), fileId, userId}
            );
            if (affected > 0) {
                auditLog->logActivity(userId, fileId, "subscription_paused",
                                      std::to_string(untilTimestamp));
            }
        } else {
            affected = db->execute(
                "UPDATE file_subscriptions SET paused_until = NULL "
                "WHERE file_id = ? AND user_id = ?",
                {fileId, userId}
            );
            if (affected > 0) {
                auditLog->logActivity(userId, fileId, "subscription_resumed", "");
            }
        }
        return affected > 0;
    }

    // 활성 구독자 목록 조회 (paused_until 만료된 사용자 포함)
    // - 채널별 enabled = 1 인 것만 포함
    // - 동일 사용자의 여러 채널은 하나의 NotificationTarget으로 병합
    std::vector<NotificationTarget> getSubscribers(const std::string& fileId) {
        std::vector<NotificationTarget> result;
        if (fileId.empty()) {
            return result;
        }

        auto now = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();

        // JOIN으로 한 번에 사용자별 채널 목록 조회
        auto rows = db->query(
            "SELECT s.user_id, c.channel "
            "FROM file_subscriptions s "
            "JOIN file_subscription_channels c "
            "  ON s.file_id = c.file_id AND s.user_id = c.user_id "
            "WHERE s.file_id = ? "
            "  AND (s.paused_until IS NULL OR s.paused_until < ?) "
            "  AND c.enabled = 1 "
            "ORDER BY s.user_id",
            {fileId, std::to_string(now)}
        );

        // user_id 기준으로 채널 병합
        std::string currentUser;
        NotificationTarget current;
        for (const auto& row : rows) {
            const std::string& uid = row.at("user_id");
            if (uid != currentUser) {
                if (!currentUser.empty()) {
                    result.push_back(current);
                }
                currentUser = uid;
                current = NotificationTarget{uid, {}};
            }
            current.channels.push_back(stringToChannel(row.at("channel")));
        }
        if (!currentUser.empty()) {
            result.push_back(current);
        }
        return result;
    }

    // 명시적 구독 외 자동 이해관계자 조회 (Q2 = B 표준안 채택)
    // - 파일 소유자 (files_versions의 최초 user_id, 추후 owners 테이블 도입 시 그쪽 우선)
    // - 마지막 수정자 (files_versions에서 timestamp DESC 1번째)
    // - 진행 중인 승인 요청의 요청자/승인자 (approval_rules.status = 'OPEN')
    // - 채널은 기본값(PUSH+WEB) 적용. 사용자별 선호 채널은 향후 user_preferences 테이블에서 조회 예정
    //
    // 04/30 - Phase A-3 (Q4=B): eventType별 이해관계자 범위 차등 적용
    //   현재는 모든 이벤트에 대해 "소유자+마지막수정자+승인관련자" 합집합을 반환.
    //   향후 확장 시 eventType별로 다음과 같이 차등 적용 예정:
    //     - "version_created"      : 소유자 (생성자 본인은 알림 불필요)
    //     - "version_updated"      : 소유자 + 다른 최근 수정자들
    //     - "status_changed"       : 소유자 + 마지막 수정자 + 승인 관련자
    //     - "approval_requested"   : 승인자 + 요청자 (소유자는 부가)
    //     - "approval_completed"   : 요청자 + 승인자 + 소유자
    //     - "version_deleted"      : 소유자 + 정책 관리자
    //   현재 의사코드는 모든 이벤트에 대해 "전체 합집합" 정책으로 통일 (보수적 기본값)
    // [Java 전환 시] 이 메서드를 StakeholderResolver 빈으로 분리 + 전략 패턴 적용
    std::vector<NotificationTarget> getDefaultStakeholders(const std::string& fileId,
                                                            const std::string& eventType) {
        std::unordered_set<std::string> userIds;  // 중복 제거용

        if (fileId.empty()) {
            return {};
        }

        // 05/06 - Phase ③ (의사코드 99% 보강): eventType별 이해관계자 차등 적용
        //   기존: 모든 이벤트에 대해 보수적 합집합 정책
        //   변경: 이벤트의 의미에 맞게 알림 대상 정밀화 → 알림 폭탄 감소
        //   매핑 규칙 (코드 직전 주석에 적혀있던 계획을 실제 분기로 옮김):
        //     "version_created"     : 소유자 (생성자 본인은 알림 불필요)
        //     "version_updated"     : 소유자 + 마지막 수정자 (다른 협업자 인지)
        //     "status_changed"      : 소유자 + 마지막 수정자 + 진행 중 승인 관련자
        //     "approval_requested"  : 승인자 + 요청자 (소유자도 부가)
        //     "approval_completed"  : 요청자 + 승인자 + 소유자
        //     "approval_cancelled"  : 요청자 + 승인자 + 소유자
        //     "approval_progress"   : 요청자 + 승인자 (진행 상황 공유)
        //     "approved_reverted"   : 소유자 + 마지막 수정자 + 승인 관련자
        //     "status_restored"     : 소유자 + 마지막 수정자
        //     "version_deleted"     : 소유자 + 관리자 목록
        //     기타 (default)        : 보수적 합집합 (예전 동작)
        //
        // 헬퍼 람다: 각 그룹 추가 시 코드 중복 줄임
        auto addOwner = [&]() {
            auto owner = db->query(
                "SELECT user_id FROM files_versions "
                "WHERE file_id = ? ORDER BY `timestamp` ASC LIMIT 1",
                {fileId}
            );
            if (!owner.empty()) userIds.insert(owner[0].at("user_id"));
        };
        auto addLastEditor = [&]() {
            auto lastEditor = db->query(
                "SELECT user_id FROM files_versions "
                "WHERE file_id = ? ORDER BY `timestamp` DESC LIMIT 1",
                {fileId}
            );
            if (!lastEditor.empty()) userIds.insert(lastEditor[0].at("user_id"));
        };
        auto addApprovalRequesters = [&]() {
            auto requesters = db->query(
                "SELECT r.entity_id FROM approval_rule_requesters r "
                "JOIN approval_rules ar ON r.rule_id = ar.id "
                "WHERE ar.file_id = ? AND ar.status = 'OPEN'",
                {fileId}
            );
            for (const auto& row : requesters) userIds.insert(row.at("entity_id"));
        };
        auto addApprovalApprovers = [&]() {
            auto approvers = db->query(
                "SELECT a.entity_id FROM approval_rule_approvers a "
                "JOIN approval_rules ar ON a.rule_id = ar.id "
                "WHERE ar.file_id = ? AND ar.status = 'OPEN'",
                {fileId}
            );
            for (const auto& row : approvers) userIds.insert(row.at("entity_id"));
        };
        // 05/14 - approval_completed/cancelled 이벤트 전용:
        //   processApprovalDecision의 마지막 broadcast 시점에는 이미 status='CLOSED'로 전환된 상태
        //   processApprovalDecision 시작 → activity INSERT → setDocumentStatus → 요청자 알림
        //     → UPDATE status='CLOSED' → broadcast notifyStakeholders("approval_completed", ...)
        //   따라서 OPEN 조건만 보면 승인자/요청자를 못 찾아 broadcast 대상이 비어버림
        //   해결: completed/cancelled 시에는 최근 CLOSED/CANCELLED 규칙도 포함
        //   "최근"의 기준: 같은 파일의 가장 최근 rule (id DESC LIMIT 1 또는 status 무관 전부)
        //   여기서는 status 무관 전부 포함 (간결성 우선, 보고서 ③ 매트릭스 의도 부합)
        auto addApprovalRequestersAny = [&]() {
            auto requesters = db->query(
                "SELECT r.entity_id FROM approval_rule_requesters r "
                "JOIN approval_rules ar ON r.rule_id = ar.id "
                "WHERE ar.file_id = ?",
                {fileId}
            );
            for (const auto& row : requesters) userIds.insert(row.at("entity_id"));
        };
        auto addApprovalApproversAny = [&]() {
            auto approvers = db->query(
                "SELECT a.entity_id FROM approval_rule_approvers a "
                "JOIN approval_rules ar ON a.rule_id = ar.id "
                "WHERE ar.file_id = ?",
                {fileId}
            );
            for (const auto& row : approvers) userIds.insert(row.at("entity_id"));
        };
        auto addAllAdmins = [&]() {
            auto admins = listAdmins();  // Phase ②에서 추가
            for (const auto& uid : admins) userIds.insert(uid);
        };

        // eventType별 분기
        if (eventType == "version_created") {
            addOwner();
        } else if (eventType == "version_updated") {
            addOwner();
            addLastEditor();
        } else if (eventType == "status_changed") {
            addOwner();
            addLastEditor();
            addApprovalRequesters();
            addApprovalApprovers();
        } else if (eventType == "approval_requested") {
            addApprovalRequesters();
            addApprovalApprovers();
            addOwner();
        } else if (eventType == "approval_completed"
                || eventType == "approval_cancelled") {
            // 05/14 - status='OPEN' 조건 없는 변형 사용
            //   완료/취소 시점에는 rule이 CLOSED/CANCELLED로 전환된 상태이므로
            addApprovalRequestersAny();
            addApprovalApproversAny();
            addOwner();
        } else if (eventType == "approval_progress") {
            addApprovalRequesters();
            addApprovalApprovers();
        } else if (eventType == "approved_reverted") {
            // 05/14 - approved_reverted는 이미 CLOSED된 APPROVED rule을 되돌리는 시점
            //         과거 결정에 참여한 승인자/요청자에게도 알림이 가야 함
            addOwner();
            addLastEditor();
            addApprovalRequestersAny();
            addApprovalApproversAny();
        } else if (eventType == "status_restored") {
            addOwner();
            addLastEditor();
        } else if (eventType == "version_deleted") {
            addOwner();
            addAllAdmins();
        } else {
            // 기타: 보수적 합집합 (예전 동작 유지)
            addOwner();
            addLastEditor();
            addApprovalRequesters();
            addApprovalApprovers();
        }

        // NotificationTarget으로 변환 (기본 채널 PUSH+WEB)
        // 사용자별 선호 채널은 향후 user_preferences 테이블에서 조회 예정
        std::vector<NotificationTarget> result;
        for (const auto& uid : userIds) {
            result.push_back({uid, {NotificationChannel::PUSH, NotificationChannel::WEB}});
        }
        return result;
    }

    // ── 헬퍼: NotificationChannel ↔ 문자열 변환 ──
    // DB 저장 시 enum 값을 문자열로, 조회 시 다시 enum으로 복원
    static std::string channelToString(NotificationChannel ch) {
        switch (ch) {
            case NotificationChannel::PUSH:  return "PUSH";
            case NotificationChannel::EMAIL: return "EMAIL";
            case NotificationChannel::WEB:   return "WEB";
        }
        return "WEB";  // 안전 기본값
    }

    static NotificationChannel stringToChannel(const std::string& s) {
        if (s == "PUSH")  return NotificationChannel::PUSH;
        if (s == "EMAIL") return NotificationChannel::EMAIL;
        return NotificationChannel::WEB;  // 알 수 없는 값은 WEB로 간주
    }

    // RD-SRS-9.9: 문서 변경 시 관련 이해관계자에게 자동 알림
    // code: lib/private/Notification/Manager.php (createNotification, notify)
    //       apps/notifications/lib/Push.php (pushToDevice)
    //       apps/notifications/lib/BackgroundJob/SendNotificationMails.php
    // Triggered by: 파일 변경 이벤트, 워크플로우 이벤트
    //
    // 04/30 - Phase A-2: targets 자동 결정 로직 추가
    //   변경 전: 호출자가 targets를 직접 지정해야 했음 → 호출부마다 대상 결정 로직 중복
    //   변경 후: targets가 비어있으면 getSubscribers + getDefaultStakeholders로
    //          자동 산출. 호환성을 위해 명시 지정도 그대로 동작
    //
    // 04/30 - Phase A-3: 자동 트리거 호출부에서 호출되도록 통합
    //   호출 위치: createInitialVersion, onDocumentModified, setDocumentStatus,
    //              processApprovalWorkflow(REQUEST), processApprovalDecision
    //   [Java 전환 시] @EventListener 기반으로 변경. 각 메서드는 이벤트만 발행하고
    //                  notifyStakeholders는 리스너로 분리
    //
    // 04/30 - Phase A-4: 중복 알림 방지 (dedup_key)
    //   동일 이벤트가 5분 내 같은 사용자에게 두 번 발생해도 무시 (UNIQUE INDEX 사용)
    //
    // 04/30 - Phase A-X: Outbox 패턴 적용
    //   채널별 즉시 발송 → outbox INSERT(PENDING) → 즉시 발송 시도 → 성공 시 SENT
    //   실패 시 PENDING 유지, processOutboxQueue가 재시도
    bool notifyStakeholders(const std::string& fileId,
                            const std::string& eventType,
                            const std::string& message,
                            const std::vector<NotificationTarget>& targets) {
        // ── 1. 대상 자동 결정 (Phase A-2) ──
        std::vector<NotificationTarget> effectiveTargets;
        if (targets.empty()) {
            effectiveTargets = getSubscribers(fileId);

            std::unordered_set<std::string> existingUsers;
            for (const auto& t : effectiveTargets) existingUsers.insert(t.userId);

            auto defaults = getDefaultStakeholders(fileId, eventType);
            for (const auto& t : defaults) {
                if (existingUsers.find(t.userId) == existingUsers.end()) {
                    effectiveTargets.push_back(t);
                }
            }
        } else {
            effectiveTargets = targets;
        }

        // 02/10 - 이벤트 timestamp를 루프 밖에서 한 번만 생성
        auto eventTimestamp = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();

        // ── 2. 사용자별 알림 처리 ──
        int dedupSkipped = 0;
        for (const auto& target : effectiveTargets) {
            // 2-1. dedup_key 생성 (Phase A-4)
            //   5분 단위 윈도우: 같은 (이벤트, 파일, 사용자) 조합이 5분 내 두 번이면 무시
            int64_t timeWindowBucket = eventTimestamp / 300;
            std::string dedupKey = eventType + ":" + fileId + ":" + target.userId
                                   + ":" + std::to_string(timeWindowBucket);

            // 2-2. notifications INSERT (UNIQUE INDEX idx_dedup으로 중복 차단)
            //   INSERT IGNORE: dedup_key 충돌 시 silent skip, 영향 row 0
            std::string notificationId = generateUUID();
            int inserted = db->execute(
                "INSERT IGNORE INTO notifications "
                "(notification_id, app, `user`, `timestamp`, "
                " object_type, object_id, subject, message, dedup_key) "
                "VALUES (?, 'files', ?, ?, 'files', ?, ?, ?, ?)",
                {notificationId, target.userId, std::to_string(eventTimestamp),
                 fileId, eventType, message, dedupKey}
            );

            if (inserted == 0) {
                // dedup_key 충돌 → 중복 알림이라 스킵
                dedupSkipped++;
                continue;
            }

            // 2-3. 채널별 outbox 큐 등록 (Phase A-X)
            //   PENDING 상태로 INSERT → 아래 즉시 발송 시도에서 SENT/유지 결정
            for (const auto& channel : target.channels) {
                enqueueOutbox(notificationId, target.userId, channel, message,
                              eventTimestamp);
            }
        }

        // ── 3. 즉시 발송 시도 (Phase A-X, Q5=A 결정) ──
        //   PENDING 항목 중 방금 추가한 것들을 즉시 처리.
        //   실패한 것은 PENDING 유지 → processOutboxQueue가 백그라운드로 재시도
        flushOutboxImmediate(eventTimestamp);

        // ── 4. 활동 로그 ──
        auditLog->logActivity("system", fileId, "notifications_sent",
            "Event: " + eventType
            + ", Recipients: " + std::to_string(effectiveTargets.size())
            + ", DedupSkipped: " + std::to_string(dedupSkipped));

        // ── 5. 배치 처리 트리거 (필요한 경우) ──
        // [Java 전환 시] Spring @Scheduled 빈으로 대체
        if (shouldBatchNotifications()) {
            scheduleBackgroundJob("SendNotificationMails");
        }

        return true;
    }

    // ============================================================
    // Outbox 패턴 헬퍼 메서드들 (Phase A-X, 04/30 추가)
    // ------------------------------------------------------------
    // [Java 전환 시] OutboxService 빈으로 분리 + @Scheduled로 큐 폴링
    // ============================================================

    // 발송 항목을 outbox에 등록 (PENDING 상태)
    void enqueueOutbox(const std::string& notificationId,
                       const std::string& userId,
                       NotificationChannel channel,
                       const std::string& payload,
                       int64_t timestamp) {
        db->execute(
            "INSERT INTO notification_outbox "
            "(notification_id, user_id, channel, payload, status, "
            " retry_count, retry_after, created_at) "
            "VALUES (?, ?, ?, ?, 'PENDING', 0, ?, ?)",
            {notificationId, userId, channelToString(channel), payload,
             std::to_string(timestamp), std::to_string(timestamp)}
        );
    }

    // 즉시 발송 시도: 방금 큐에 들어간 PENDING 항목들을 처리
    //   - 성공: status='SENT', sent_at 기록
    //   - 실패: PENDING 유지 → processOutboxQueue가 재시도
    void flushOutboxImmediate(int64_t cutoffTimestamp) {
        auto pending = db->query(
            "SELECT id, notification_id, user_id, channel, payload "
            "FROM notification_outbox "
            "WHERE status = 'PENDING' AND retry_count = 0 "
            "  AND created_at >= ? "
            "ORDER BY id ASC",
            {std::to_string(cutoffTimestamp)}
        );

        for (const auto& row : pending) {
            attemptDelivery(row);
        }
    }

    // 백그라운드 잡: PENDING 큐 처리 (재시도 정책 적용)
    //   주기적 호출 (예: 1분마다). retry_after가 도래한 항목만 처리.
    //   [Java 전환 시] @Scheduled(fixedDelay=60000) 메서드로 변환
    int processOutboxQueue() {
        auto now = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();

        // 재시도 시각이 도래한 PENDING 항목만 가져옴
        auto pending = db->query(
            "SELECT id, notification_id, user_id, channel, payload, retry_count "
            "FROM notification_outbox "
            "WHERE status = 'PENDING' AND retry_after <= ? "
            "ORDER BY retry_after ASC LIMIT 100",
            {std::to_string(now)}
        );

        int processed = 0;
        for (const auto& row : pending) {
            attemptDelivery(row);
            processed++;
        }
        return processed;
    }

    // 단일 outbox 항목 발송 시도
    //   성공: status='SENT', sent_at 기록
    //   실패: retry_count++, retry_after = now + backoff(retry_count)
    //         retry_count >= 3이면 status='DLQ'로 이동
    void attemptDelivery(const std::map<std::string, std::string>& outboxRow) {
        const std::string& outboxId       = outboxRow.at("id");
        const std::string& userId         = outboxRow.at("user_id");
        const std::string& channelStr     = outboxRow.at("channel");
        const std::string& payload        = outboxRow.at("payload");
        int retryCount = outboxRow.count("retry_count")
                         ? std::stoi(outboxRow.at("retry_count")) : 0;

        NotificationChannel channel = stringToChannel(channelStr);
        bool success = false;
        std::string errorMsg;

        try {
            // 채널별 실제 발송 (기존 로직 그대로 이전)
            switch (channel) {
                case NotificationChannel::PUSH: {
                    auto pushTokens = db->query(
                        "SELECT token FROM notifications_pushhash WHERE uid = ?",
                        {userId}
                    );
                    if (pushTokens.empty()) {
                        // 토큰 없음 → 발송 불가지만 재시도해도 의미 없음 → 성공 처리
                        success = true;
                    } else {
                        for (const auto& token : pushTokens) {
                            sendPushNotification(token.at("token"), payload);
                        }
                        success = true;
                    }
                    break;
                }
                case NotificationChannel::EMAIL: {
                    auto eventTimestamp = std::chrono::duration_cast<std::chrono::seconds>(
                        std::chrono::system_clock::now().time_since_epoch()
                    ).count();
                    db->execute(
                        "INSERT INTO notifications_mq "
                        "(amq_timestamp, amq_affecteduser, amq_appid, amq_subject, amq_subjectparams) "
                        "VALUES (?, ?, 'files', ?, ?)",
                        {std::to_string(eventTimestamp), userId, "notification", payload}
                    );
                    success = true;
                    break;
                }
                case NotificationChannel::WEB:
                    // 웹은 notifications 테이블에 이미 저장됨 → 추가 발송 없음
                    success = true;
                    break;
            }
        } catch (const std::exception& e) {
            errorMsg = e.what();
            success = false;
        }

        auto now = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();

        if (success) {
            db->execute(
                "UPDATE notification_outbox "
                "SET status = 'SENT', sent_at = ? "
                "WHERE id = ?",
                {std::to_string(now), outboxId}
            );
        } else {
            int newRetryCount = retryCount + 1;
            if (newRetryCount >= 3) {
                // DLQ로 이동 (Q6=A 결정: 3회 재시도 후 dead letter)
                db->execute(
                    "UPDATE notification_outbox "
                    "SET status = 'DLQ', retry_count = ?, last_error = ? "
                    "WHERE id = ?",
                    {std::to_string(newRetryCount), errorMsg, outboxId}
                );
                auditLog->logActivity("system", userId, "notification_dlq",
                    "Outbox " + outboxId + " moved to DLQ after " +
                    std::to_string(newRetryCount) + " attempts");
            } else {
                int64_t backoffSeconds = calculateBackoff(newRetryCount);
                db->execute(
                    "UPDATE notification_outbox "
                    "SET retry_count = ?, retry_after = ?, last_error = ? "
                    "WHERE id = ?",
                    {std::to_string(newRetryCount),
                     std::to_string(now + backoffSeconds),
                     errorMsg, outboxId}
                );
            }
        }
    }

    // 지수 백오프: retry_count 1=60초, 2=300초(5분), 3=1800초(30분)
    //   Q6=A 결정: 3회 재시도 후 DLQ
    static int64_t calculateBackoff(int retryCount) {
        switch (retryCount) {
            case 1: return 60;     // 1분
            case 2: return 300;    // 5분
            case 3: return 1800;   // 30분
            default: return 1800;  // 안전 기본값
        }
    }
    // 현재 수동 호출 방식으로 구현되어 있음 추후 framework 연동을 통하여 이벤트 연동을 통하여 자동으로 호출할 수 있도록 구현이 필요

    // ============================================================
    // RD-SRS-9.10 보존 정책 CRUD + 차등 적용 (Phase A-10, 05/06 추가)
    // ------------------------------------------------------------
    // 정책 관리:
    //   createRetentionPolicy   - 새 정책 생성
    //   getRetentionPolicy      - 정책 단건 조회
    //   updateRetentionPolicy   - 정책 수정 (즉시 적용 옵션)
    //   deactivatePolicy        - 일시 비활성화 (is_active=0, 결정 ⑥)
    //   deletePolicy            - 완전 삭제 (결정 ⑥)
    //   listPolicies            - 정책 목록 조회 (관리 UI용)
    // 적용:
    //   evaluatePolicy          - 파일에 적용될 정책 결정 (구체성 우선)
    //   applyToAllFiles         - 정책 영향 파일 일괄 정리 (백그라운드 잡)
    // ============================================================

    // 정책 생성 (결정 ③)
    //   scope_id 의미: GLOBAL은 무시(빈 문자열), USER는 user_id, FOLDER는 폴더 경로, FILE은 file_id
    //   반환: 생성된 정책 ID (UUID), 실패 시 빈 문자열
    std::string createRetentionPolicy(const std::string& adminUserId,
                                       RetentionPolicyScope scope,
                                       const std::string& scopeId,
                                       const RetentionPolicy& params) {
        // 권한 체크 (결정 ⑤): 정책 관리는 관리자 전용
        if (!isAdmin(adminUserId)) {
            auditLog->logActivity(adminUserId, "", "policy_create_denied",
                                  "Non-admin attempted to create retention policy");
            return "";
        }

        std::string policyId = generateUUID();
        auto now = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();

        db->execute(
            "INSERT INTO retention_policies "
            "(id, scope_type, scope_id, min_days, max_days, max_versions, "
            " auto_cleanup, is_active, created_at, updated_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?)",
            {policyId, retentionScopeToString(scope),
             scopeId,
             std::to_string(params.minDays),
             std::to_string(params.maxDays),
             std::to_string(params.maxVersions),
             std::to_string(params.autoCleanup ? 1 : 0),
             std::to_string(now), std::to_string(now)}
        );

        auditLog->logActivity(adminUserId, scopeId, "policy_created",
            "Policy " + policyId + " for " + retentionScopeToString(scope));
        return policyId;
    }

    // 정책 단건 조회 (결정 ③)
    //   반환: 정책 row 맵. 미존재 시 빈 맵
    std::map<std::string, std::string> getRetentionPolicy(const std::string& policyId) {
        auto rows = db->query(
            "SELECT id, scope_type, scope_id, min_days, max_days, max_versions, "
            "       auto_cleanup, is_active, created_at, updated_at "
            "FROM retention_policies WHERE id = ? LIMIT 1",
            {policyId}
        );
        if (rows.empty()) return {};
        return rows[0];
    }

    // 정책 수정 (결정 ③④)
    //   immediateApply=true: 영향 파일에 즉시 일괄 적용 (applyToAllFiles 호출)
    //   immediateApply=false: 다음 파일 수정 시점부터 자연 적용 (lazy)
    bool updateRetentionPolicy(const std::string& adminUserId,
                                const std::string& policyId,
                                const RetentionPolicy& newParams,
                                bool immediateApply) {
        if (!isAdmin(adminUserId)) {
            auditLog->logActivity(adminUserId, "", "policy_update_denied",
                                  "Non-admin attempted to update policy " + policyId);
            return false;
        }

        auto now = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();

        int affected = db->execute(
            "UPDATE retention_policies "
            "SET min_days = ?, max_days = ?, max_versions = ?, "
            "    auto_cleanup = ?, updated_at = ? "
            "WHERE id = ?",
            {std::to_string(newParams.minDays),
             std::to_string(newParams.maxDays),
             std::to_string(newParams.maxVersions),
             std::to_string(newParams.autoCleanup ? 1 : 0),
             std::to_string(now), policyId}
        );

        if (affected == 0) return false;  // 미존재

        auditLog->logActivity(adminUserId, "", "policy_updated",
            "Policy " + policyId + " updated, immediateApply=" +
            (immediateApply ? "true" : "false"));

        if (immediateApply) {
            applyToAllFiles(policyId);
        }
        return true;
    }

    // 정책 비활성화 (결정 ⑥, soft delete)
    //   is_active = 0 → evaluatePolicy에서 매칭에서 제외
    //   이력 보존, 향후 재활성화 가능
    bool deactivatePolicy(const std::string& adminUserId, const std::string& policyId) {
        if (!isAdmin(adminUserId)) return false;

        auto now = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();
        int affected = db->execute(
            "UPDATE retention_policies SET is_active = 0, updated_at = ? "
            "WHERE id = ?",
            {std::to_string(now), policyId}
        );
        if (affected > 0) {
            auditLog->logActivity(adminUserId, "", "policy_deactivated", policyId);
        }
        return affected > 0;
    }

    // 정책 완전 삭제 (결정 ⑥, hard delete)
    //   row 자체 제거. 이력 사라짐. 신중하게 사용.
    bool deletePolicy(const std::string& adminUserId, const std::string& policyId) {
        if (!isAdmin(adminUserId)) return false;

        int affected = db->execute(
            "DELETE FROM retention_policies WHERE id = ?",
            {policyId}
        );
        if (affected > 0) {
            auditLog->logActivity(adminUserId, "", "policy_deleted", policyId);
        }
        return affected > 0;
    }

    // 정책 목록 조회 (관리 UI용)
    std::vector<std::map<std::string, std::string>> listPolicies(bool activeOnly) {
        if (activeOnly) {
            return db->query(
                "SELECT id, scope_type, scope_id, min_days, max_days, max_versions, "
                "       auto_cleanup, is_active, created_at, updated_at "
                "FROM retention_policies WHERE is_active = 1 "
                "ORDER BY scope_type, scope_id",
                {}
            );
        } else {
            return db->query(
                "SELECT id, scope_type, scope_id, min_days, max_days, max_versions, "
                "       auto_cleanup, is_active, created_at, updated_at "
                "FROM retention_policies "
                "ORDER BY is_active DESC, scope_type, scope_id",
                {}
            );
        }
    }

    // 파일에 적용될 정책 결정 (결정 ② 구체성 우선 cascade)
    //   FILE → FOLDER → USER → GLOBAL 순서로 활성 정책 검색.
    //   첫 번째 매칭되는 정책 하나만 사용 (머지 안 함).
    //   미매칭 시 기본 정책 (RetentionPolicy 기본값) 반환.
    // [Java 전환 시] @Cacheable로 fileId별 결과 캐시 (정책 변경 시 evict)
    RetentionPolicy evaluatePolicy(const std::string& fileId) {
        // 1. FILE 단위 정책 (가장 구체적)
        auto filePolicy = db->query(
            "SELECT min_days, max_days, max_versions, auto_cleanup "
            "FROM retention_policies "
            "WHERE scope_type = 'FILE' AND scope_id = ? AND is_active = 1 "
            "ORDER BY updated_at DESC LIMIT 1",
            {fileId}
        );
        if (!filePolicy.empty()) {
            return policyFromRow(filePolicy[0]);
        }

        // 2. FOLDER 단위 정책 (파일 경로의 부모 폴더 찾기)
        //   의사코드 단순화: file_id의 prefix 매칭으로 폴더 추정
        //   [Java 전환 시] FileMetadata에서 정확한 폴더 경로 추출
        std::string folderPath = extractFolderPath(fileId);
        if (!folderPath.empty()) {
            auto folderPolicy = db->query(
                "SELECT min_days, max_days, max_versions, auto_cleanup "
                "FROM retention_policies "
                "WHERE scope_type = 'FOLDER' AND scope_id = ? AND is_active = 1 "
                "ORDER BY updated_at DESC LIMIT 1",
                {folderPath}
            );
            if (!folderPolicy.empty()) {
                return policyFromRow(folderPolicy[0]);
            }
        }

        // 3. USER 단위 정책 (파일 소유자 기준)
        auto ownerRows = db->query(
            "SELECT user_id FROM files_versions "
            "WHERE file_id = ? ORDER BY `timestamp` ASC LIMIT 1",
            {fileId}
        );
        if (!ownerRows.empty()) {
            std::string ownerId = ownerRows[0].at("user_id");
            auto userPolicy = db->query(
                "SELECT min_days, max_days, max_versions, auto_cleanup "
                "FROM retention_policies "
                "WHERE scope_type = 'USER' AND scope_id = ? AND is_active = 1 "
                "ORDER BY updated_at DESC LIMIT 1",
                {ownerId}
            );
            if (!userPolicy.empty()) {
                return policyFromRow(userPolicy[0]);
            }
        }

        // 4. GLOBAL 정책
        auto globalPolicy = db->query(
            "SELECT min_days, max_days, max_versions, auto_cleanup "
            "FROM retention_policies "
            "WHERE scope_type = 'GLOBAL' AND is_active = 1 "
            "ORDER BY updated_at DESC LIMIT 1",
            {}
        );
        if (!globalPolicy.empty()) {
            return policyFromRow(globalPolicy[0]);
        }

        // 5. 미매칭 시 시스템 기본값
        return RetentionPolicy{};  // 모든 필드 기본값
    }

    // 정책 일괄 적용 (결정 ③④, 백그라운드 잡)
    //   특정 정책의 영향 파일들에 applyVersionRetentionPolicy 호출
    //   처리 파일 수 반환
    // [Java 전환 시] @Async + @Scheduled로 배치 처리. 큰 규모는 페이지네이션
    int applyToAllFiles(const std::string& policyId) {
        auto policyRow = getRetentionPolicy(policyId);
        if (policyRow.empty()) return 0;

        std::string scopeType = policyRow.at("scope_type");
        std::string scopeId   = policyRow.at("scope_id");

        // 영향 파일 목록 조회
        std::vector<std::map<std::string, std::string>> affectedFiles;
        if (scopeType == "FILE") {
            affectedFiles.push_back({{"file_id", scopeId}});
        } else if (scopeType == "FOLDER") {
            // 폴더 prefix로 매칭되는 파일들
            affectedFiles = db->query(
                "SELECT DISTINCT file_id FROM files_versions WHERE file_id LIKE ?",
                {scopeId + "%"}
            );
        } else if (scopeType == "USER") {
            affectedFiles = db->query(
                "SELECT DISTINCT file_id FROM files_versions WHERE user_id = ?",
                {scopeId}
            );
        } else {  // GLOBAL
            affectedFiles = db->query(
                "SELECT DISTINCT file_id FROM files_versions", {}
            );
        }

        // 각 파일에 정책 적용
        RetentionPolicy params = policyFromRow(policyRow);
        int processed = 0;
        for (const auto& fileRow : affectedFiles) {
            applyVersionRetentionPolicy(fileRow.at("file_id"), params);
            processed++;
        }

        auditLog->logActivity("system", "", "policy_bulk_applied",
            "Policy " + policyId + " applied to " + std::to_string(processed) + " files");
        return processed;
    }

    // ── A-10 헬퍼들 ──

    static std::string retentionScopeToString(RetentionPolicyScope s) {
        switch (s) {
            case RetentionPolicyScope::GLOBAL: return "GLOBAL";
            case RetentionPolicyScope::USER:   return "USER";
            case RetentionPolicyScope::FOLDER: return "FOLDER";
            case RetentionPolicyScope::FILE:   return "FILE";
        }
        return "GLOBAL";
    }

    // DB row → RetentionPolicy 구조체 변환
    static RetentionPolicy policyFromRow(const std::map<std::string, std::string>& row) {
        RetentionPolicy p;
        if (row.count("min_days"))     p.minDays     = std::stoi(row.at("min_days"));
        if (row.count("max_days"))     p.maxDays     = std::stoi(row.at("max_days"));
        if (row.count("max_versions")) p.maxVersions = std::stoi(row.at("max_versions"));
        if (row.count("auto_cleanup")) p.autoCleanup = (row.at("auto_cleanup") != "0");
        return p;
    }

    // file_id에서 폴더 경로 추출 (의사코드 단순화)
    //   "/projects/legal/contract.pdf" → "/projects/legal/"
    //   확정된 폴더 모델이 없는 경우 빈 문자열 반환 (FOLDER 단계 스킵)
    // [Java 전환 시] Nextcloud의 IFile.getParent().getPath() 사용
    std::string extractFolderPath(const std::string& fileId) {
        auto pos = fileId.rfind('/');
        if (pos == std::string::npos) return "";
        return fileId.substr(0, pos + 1);  // trailing slash 포함
    }

    // 사용자 명시 버전 삭제 (Phase A-10 결정 ⑤: 정책 위반 처리)
    //   일반 사용자: 적용 정책의 minDays 이내 버전은 삭제 거부
    //   관리자: forceDelete=true로 정책 우회 가능 (단, 항상 로그 기록)
    //   추가 시나리오: 개인정보 삭제 요청, 잘못 업로드된 파일 즉시 제거 등
    // [Java 전환 시] @PreAuthorize로 forceDelete 권한 분리. 감사 로그는 별도 테이블
    bool deleteVersion(const std::string& userId,
                       const std::string& versionId,
                       bool forceDelete) {
        // 1. 버전 존재 확인 + 메타데이터 조회
        auto versionRows = db->query(
            "SELECT file_id, `timestamp`, user_id FROM files_versions "
            "WHERE version_id = ? LIMIT 1",
            {versionId}
        );
        if (versionRows.empty()) {
            return false;
        }
        const std::string& fileId = versionRows[0].at("file_id");
        int64_t versionTimestamp = std::stoll(versionRows[0].at("timestamp"));

        // 05/14 - 권한 체크 추가: 파일 소유자 또는 관리자만 삭제 가능
        //   기존: minDays 미위반 시 누구나 삭제 가능 → 보안 이슈
        //   해결: 파일의 최초 버전 작성자(=소유자) 또는 관리자만 허용
        //   주의: forceDelete의 관리자 체크는 별도 (minDays 우회용)
        bool isOwnerOfFile = false;
        auto ownerRows = db->query(
            "SELECT user_id FROM files_versions "
            "WHERE file_id = ? ORDER BY `timestamp` ASC LIMIT 1",
            {fileId}
        );
        if (!ownerRows.empty() && ownerRows[0].at("user_id") == userId) {
            isOwnerOfFile = true;
        }
        if (!isOwnerOfFile && !isAdmin(userId)) {
            auditLog->logActivity(userId, fileId, "version_delete_denied",
                "User has no permission to delete version " + versionId);
            return false;
        }

        // 2. 정책 평가 (cascade)
        RetentionPolicy policy = evaluatePolicy(fileId);

        // 3. minDays 검증 (정책 위반 체크)
        auto now = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();
        int64_t versionAge = now - versionTimestamp;  // 초 단위

        bool violatesMinDays = (policy.minDays > 0
                                && versionAge < (int64_t)policy.minDays * 86400);

        if (violatesMinDays) {
            if (!forceDelete) {
                // 일반 사용자: 거부
                auditLog->logActivity(userId, fileId, "version_delete_denied",
                    "Version " + versionId + " is within minDays protection ("
                    + std::to_string(policy.minDays) + " days)");
                return false;
            }
            if (!isAdmin(userId)) {
                // forceDelete 요청했으나 관리자 아님: 거부
                auditLog->logActivity(userId, fileId, "version_force_delete_denied",
                    "Non-admin attempted forceDelete on version " + versionId);
                return false;
            }
            // 관리자가 명시적으로 forceDelete: 허용하되 강조 로그
            auditLog->logActivity(userId, fileId, "version_force_deleted",
                "Admin overrode minDays policy for version " + versionId);
        }

        // 4. 삭제 실행
        int affected = db->execute(
            "DELETE FROM files_versions WHERE version_id = ?",
            {versionId}
        );
        if (affected > 0) {
            // 백업 파일도 제거
            fileStorage->deleteFile("files_versions/" + versionId);
            auditLog->logActivity(userId, fileId, "version_deleted",
                "Version " + versionId + " deleted by " + userId);

            // 05/14 - 자동 트리거 추가 (③ 매트릭스의 version_deleted: 소유자 + 관리자 전원)
            // [Java 전환 시] VersionDeletedEvent 발행으로 분리
            notifyStakeholders(fileId, "version_deleted",
                "Version " + versionId + " was deleted by " + userId, {});
            return true;
        }
        return false;
    }

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

    // ============================================================
    // 백그라운드 잡 메서드 모음 (Phase ④, 05/06 추가)
    // ------------------------------------------------------------
    // 의사코드 단계: 메서드 본체 (a)는 구현, 자동 실행 인프라 (b)는 주석으로 표시
    //
    // ───── 자동 실행 인프라 (b) - Java 전환 시 구현 ─────
    //
    //   메서드별 권장 스케줄 (의사코드 표현):
    //
    //     scheduledOutboxFlush       : 1분마다 실행
    //         [Java 전환 시] @Scheduled(fixedDelay = 60_000)
    //
    //     scheduledRetentionCleanup  : 매일 새벽 02시 실행
    //         [Java 전환 시] @Scheduled(cron = "0 0 2 * * ?")
    //
    //     scheduledExpiredDelegationsCleanup : 매시간 실행
    //         [Java 전환 시] @Scheduled(cron = "0 0 * * * ?")
    //
    //     scheduledOldNotificationsCleanup : 매주 일요일 03시 실행
    //         [Java 전환 시] @Scheduled(cron = "0 0 3 ? * SUN")
    //
    //   현재 의사코드에서는 자동 호출 인프라가 없으므로, 외부 CLI/cron이
    //   주기적으로 이 메서드들을 호출한다고 가정. 실제 동작은 Java 전환 시.
    //
    //   대안 인프라:
    //     - Linux cron: 외부 명령으로 호출
    //     - C++ std::thread + condition_variable: 별도 스레드 폴링
    //     - 메시지 큐 (RabbitMQ, Redis): 이벤트 기반 트리거
    //   세 가지 모두 의사코드 단계엔 부적합. Spring @Scheduled로 Java에서 깔끔히 풀림.
    // ============================================================

    // Outbox 큐 자동 처리 잡 (1분 주기)
    //   기존 processOutboxQueue를 그대로 호출. 이름만 다른 별칭.
    //   목적: "이 메서드가 백그라운드 잡임"을 코드 의도로 표현
    int scheduledOutboxFlush() {
        return processOutboxQueue();
    }

    // 보존 정책 자동 정리 잡 (매일 새벽 실행 권장)
    //   모든 파일에 대해 evaluatePolicy → applyVersionRetentionPolicy
    //   대량 처리 가능: 1000개 파일 단위로 페이지네이션
    int scheduledRetentionCleanup() {
        // 모든 파일 ID 목록 (페이지네이션)
        // [Java 전환 시] Stream API + chunkSize 처리. 또는 Spring Batch
        constexpr int kChunkSize = 1000;
        int totalProcessed = 0;
        int offset = 0;

        while (true) {
            auto fileBatch = db->query(
                "SELECT DISTINCT file_id FROM files_versions "
                "ORDER BY file_id LIMIT " + std::to_string(kChunkSize) +
                " OFFSET " + std::to_string(offset),
                {}
            );
            if (fileBatch.empty()) break;

            for (const auto& row : fileBatch) {
                const std::string& fileId = row.at("file_id");
                RetentionPolicy policy = evaluatePolicy(fileId);
                applyVersionRetentionPolicy(fileId, policy);
                totalProcessed++;
            }

            if (fileBatch.size() < kChunkSize) break;  // 마지막 페이지
            offset += kChunkSize;
        }

        auditLog->logActivity("system", "", "scheduled_retention_done",
            "Processed " + std::to_string(totalProcessed) + " files");
        return totalProcessed;
    }

    // 만료된 위임 정리 잡 (매시간 실행 권장)
    //   expires_at이 지난 임시 위임을 DB에서 제거
    //   영구 위임(expires_at IS NULL)은 보존
    int scheduledExpiredDelegationsCleanup() {
        auto now = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();

        int affected = db->execute(
            "DELETE FROM approval_delegations "
            "WHERE expires_at IS NOT NULL AND expires_at <= ?",
            {std::to_string(now)}
        );
        if (affected > 0) {
            auditLog->logActivity("system", "", "scheduled_delegations_cleanup",
                "Removed " + std::to_string(affected) + " expired delegations");
        }
        return affected;
    }

    // 오래된 알림/Outbox 정리 잡 (매주 실행 권장)
    //   30일 이상 지난 SENT/DLQ 큐, 90일 이상 지난 알림 정리
    //   [Java 전환 시] 정리 기간을 application.yml로 외부화
    int scheduledOldNotificationsCleanup() {
        auto now = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();

        constexpr int64_t kOutboxRetentionDays = 30;
        constexpr int64_t kNotificationRetentionDays = 90;

        int outboxRemoved = db->execute(
            "DELETE FROM notification_outbox "
            "WHERE status IN ('SENT', 'DLQ') AND sent_at IS NOT NULL "
            "  AND sent_at < ?",
            {std::to_string(now - kOutboxRetentionDays * 86400)}
        );

        int notifsRemoved = db->execute(
            "DELETE FROM notifications WHERE `timestamp` < ?",
            {std::to_string(now - kNotificationRetentionDays * 86400)}
        );

        auditLog->logActivity("system", "", "scheduled_notifications_cleanup",
            "Removed " + std::to_string(outboxRemoved) + " outbox, " +
            std::to_string(notifsRemoved) + " notifications");
        return outboxRemoved + notifsRemoved;
    }

    // ============================================================
    // 사용자 측 조회 API (Phase ⑤⑥⑦, 05/06 추가)
    // ------------------------------------------------------------
    // 데모 시나리오에서 직접 보이는 기능들:
    //   ⑤ getUserNotifications   : 사용자가 자기 알림 보기
    //   ⑥ markNotificationRead   : 알림 읽음 처리 + getUnreadCount
    //   ⑦ getApprovalProgress    : 승인 진행 상황 조회 (카운터+이력+미결자)
    // [Java 전환 시] @RestController로 매핑. 페이지네이션은 Pageable 사용.
    // ============================================================

    // ⑤ 알림 조회 API
    //   사용자가 자기 받은 알림 목록을 시간 역순으로 조회
    //   unreadOnly=true: 안 읽은 것만
    //   limit/offset: 페이지네이션
    std::vector<std::map<std::string, std::string>> getUserNotifications(
            const std::string& userId,
            bool unreadOnly,
            int limit = 50,
            int offset = 0) {
        constexpr int kMinLimit = 1;
        constexpr int kMaxLimit = 100;
        if (limit < kMinLimit) limit = kMinLimit;
        if (limit > kMaxLimit) limit = kMaxLimit;
        if (offset < 0) offset = 0;

        std::string baseQuery =
            "SELECT notification_id, app, `timestamp`, "
            "       object_type, object_id, subject, message, read_at "
            "FROM notifications "
            "WHERE `user` = ? ";
        if (unreadOnly) {
            baseQuery += "AND read_at IS NULL ";
        }
        baseQuery +=
            "ORDER BY `timestamp` DESC "
            "LIMIT " + std::to_string(limit) +
            " OFFSET " + std::to_string(offset);

        return db->query(baseQuery, {userId});
    }

    // ⑤ 알림 전체 카운트 (페이지네이션 메타데이터)
    int64_t countUserNotifications(const std::string& userId, bool unreadOnly) {
        std::string q = "SELECT COUNT(*) AS cnt FROM notifications WHERE `user` = ?";
        if (unreadOnly) q += " AND read_at IS NULL";
        auto rows = db->query(q, {userId});
        if (rows.empty()) return 0;
        return std::stoll(rows[0].at("cnt"));
    }

    // ⑥ 알림 읽음 처리
    //   본인의 알림만 읽음 처리 가능 (보안)
    //   이미 읽은 알림은 read_at 갱신 안 됨 (최초 읽은 시각 보존)
    bool markNotificationRead(const std::string& userId,
                              const std::string& notificationId) {
        auto now = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();

        // user 일치 + 아직 안 읽은 경우만 갱신
        int affected = db->execute(
            "UPDATE notifications SET read_at = ? "
            "WHERE notification_id = ? AND `user` = ? AND read_at IS NULL",
            {std::to_string(now), notificationId, userId}
        );
        return affected > 0;
    }

    // ⑥ 일괄 읽음 처리 (전체 또는 특정 파일 관련)
    //   fileId가 비어있으면 사용자의 모든 알림 읽음 처리
    int markAllNotificationsRead(const std::string& userId, const std::string& fileId) {
        auto now = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();

        if (fileId.empty()) {
            return db->execute(
                "UPDATE notifications SET read_at = ? "
                "WHERE `user` = ? AND read_at IS NULL",
                {std::to_string(now), userId}
            );
        } else {
            return db->execute(
                "UPDATE notifications SET read_at = ? "
                "WHERE `user` = ? AND object_id = ? AND read_at IS NULL",
                {std::to_string(now), userId, fileId}
            );
        }
    }

    // ⑥ 안 읽은 알림 카운트 (UI 배지 표시용)
    int64_t getUnreadCount(const std::string& userId) {
        return countUserNotifications(userId, true);
    }

    // ⑦ 승인 진행 상황 조회 API (Q3=C 결정)
    //   반환 구조:
    //     - rule:        규칙 정보 (id, file_id, mode, status)
    //     - counters:    {required, received_approvals, received_rejections, total_approvers}
    //     - decisions:   결정 이력 (각 승인자가 언제 어떻게 결정했는지)
    //     - pending:     미결 승인자 목록 (SEQUENTIAL은 다음 차례 표시 가능)
    //   반환 타입: 중첩 맵 (의사코드 단순화). Java 전환 시 ApprovalProgressDto 같은 객체로
    struct ApprovalProgress {
        std::map<std::string, std::string> rule;
        std::map<std::string, std::string> counters;
        std::vector<std::map<std::string, std::string>> decisions;
        std::vector<std::map<std::string, std::string>> pending;
    };

    ApprovalProgress getApprovalProgress(const std::string& fileId) {
        ApprovalProgress progress;

        // 1. OPEN 규칙 우선 조회. 없으면 가장 최근 CLOSED/CANCELLED
        auto rules = db->query(
            "SELECT id, file_id, status, consensus_mode, "
            "       required_approvals, received_approvals, received_rejections "
            "FROM approval_rules "
            "WHERE file_id = ? "
            "ORDER BY (status = 'OPEN') DESC, id DESC "
            "LIMIT 1",
            {fileId}
        );
        if (rules.empty()) {
            return progress;  // 승인 이력 없음 → 빈 progress 반환
        }
        progress.rule = rules[0];
        std::string ruleId = rules[0].at("id");

        // 2. 카운터
        auto totalRows = db->query(
            "SELECT COUNT(*) AS cnt FROM approval_rule_approvers WHERE rule_id = ?",
            {ruleId}
        );
        std::string totalApprovers = totalRows.empty() ? "0" : totalRows[0].at("cnt");
        progress.counters = {
            {"required",            rules[0].at("required_approvals")},
            {"received_approvals",  rules[0].at("received_approvals")},
            {"received_rejections", rules[0].at("received_rejections")},
            {"total_approvers",     totalApprovers},
            {"mode",                rules[0].at("consensus_mode")}
        };

        // 3. 결정 이력 (시간 순)
        progress.decisions = db->query(
            "SELECT user_id, action, `timestamp`, comment "
            "FROM approval_activity "
            "WHERE rule_id = ? "
            "ORDER BY `timestamp` ASC",
            {ruleId}
        );

        // 4. 미결 승인자 목록 (sequence_order 순)
        progress.pending = db->query(
            "SELECT entity_id AS user_id, sequence_order "
            "FROM approval_rule_approvers "
            "WHERE rule_id = ? "
            "  AND entity_id NOT IN ("
            "    SELECT user_id FROM approval_activity "
            "    WHERE rule_id = ? AND action IN ('approved', 'rejected')"
            "  ) "
            "ORDER BY sequence_order ASC, entity_id ASC",
            {ruleId, ruleId}
        );

        return progress;
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

        // 05/06 - Phase ① 위임 권한 체크 (의사코드 99% 보강)
        //   본인이 직접 승인자가 아니면, 활성 위임을 받은 사용자 중에 승인자가 있는지 확인
        //   delegatedFor: 위임받아 대신 결정하는 경우의 원래 승인자 ID (감사 추적용)
        std::string delegatedFor;  // 빈 문자열 = 본인 승인 권한, 값 있음 = 위임받음
        if (approverCheck.empty()) {
            auto delegators = getActiveDelegatorsOf(userId);
            for (const auto& delegatorId : delegators) {
                auto delegatedCheck = db->query(
                    "SELECT rule_id FROM approval_rule_approvers "
                    "WHERE entity_id = ? AND rule_id IN "
                    "(SELECT id FROM approval_rules WHERE tag_pending = ? AND file_id = ? AND status = 'OPEN')",
                    {delegatorId, TAG_UNDER_REVIEW, fileId}
                );
                if (!delegatedCheck.empty()) {
                    approverCheck = delegatedCheck;
                    delegatedFor = delegatorId;
                    break;  // 첫 번째 매칭 위임자 사용
                }
            }
        }

        if (approverCheck.empty()) {
            return false;  // 권한 없음 (본인도 위임받은 것도 없음)
        }

        std::string ruleId = approverCheck[0]["rule_id"];

        // 05/06 - Phase A-9 (결정 ④): 같은 승인자 재결정 차단
        //   같은 승인자가 이미 결정한 경우 두 번째 결정은 거부.
        //   사유: 합의 카운터의 정합성 보장. 마음 변경이 필요하면 cancel 후 재요청.
        //   05/06 위임 보강: 위임받은 경우 위임자(delegatedFor)와 본인(userId)
        //                    어느 쪽도 이미 결정했으면 차단
        // [Java 전환 시] 도입 기업이 "변경 허용" 정책 원하면 Strategy로 분기
        std::string priorCheckTargetUser = delegatedFor.empty() ? userId : delegatedFor;
        auto priorDecision = db->query(
            "SELECT id FROM approval_activity "
            "WHERE rule_id = ? AND user_id IN (?, ?) AND action IN ('approved', 'rejected') "
            "LIMIT 1",
            {ruleId, userId, priorCheckTargetUser}
        );
        if (!priorDecision.empty()) {
            auditLog->logActivity(userId, fileId, "approval_duplicate_denied",
                "User " + userId + " already decided on rule " + ruleId);
            return false;
        }

        // 05/06 - Phase A-9: SEQUENTIAL 모드의 경우 본인 차례인지 검증
        //   sequence_order가 작은 순서부터 진행. 본인보다 앞 순서가 모두 결정된 경우만 통과.
        // 05/14 - 위임 시나리오 보강: 위임받은 경우 위임자(delegatedFor)의 차례를 검사
        //   문제: SEQUENTIAL+위임 시 위임받은 본인은 승인자 명단에 없어
        //         isUserTurnInSequence가 myOrder.empty()로 항상 false 반환 → 결정 영구 거부
        //   해결: delegatedFor 있으면 위임자의 sequence_order 기준으로 차례 판단
        std::string turnCheckUser = delegatedFor.empty() ? userId : delegatedFor;
        if (!isUserTurnInSequence(ruleId, turnCheckUser)) {
            auditLog->logActivity(userId, fileId, "approval_out_of_sequence",
                "User " + userId + " attempted decision out of sequence on rule " + ruleId);
            return false;
        }

        // 2. 결정 액션 기록 (상태 전이 전에 먼저 기록 — 합의 평가 입력으로 사용)
        // 05/06 - Phase ① 위임 보강:
        //   user_id = 실제 결정자(userId, 본인 또는 피위임자)
        //   comment에 "[delegated for {원래승인자}]" 접두 추가하여 감사 추적 가능
        //   합의 카운터는 위임자 기준이 아니라 결정 자체로 카운트 (1 결정 = 1 카운트)
        auto timestamp = std::to_string(std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()).count());
        std::string activityComment = comment;
        if (!delegatedFor.empty()) {
            activityComment = "[delegated for " + delegatedFor + "] " + comment;
        }
        db->execute("INSERT INTO approval_activity (rule_id, user_id, action, "
                    "`timestamp`, comment) VALUES (?, ?, ?, ?, ?)",
                    {ruleId, userId, actionTag, timestamp, activityComment});

        // 05/06 - Phase A-9: received_approvals/received_rejections 카운터 갱신
        bool isApprove = (std::string(actionTag) == "approved");
        if (isApprove) {
            db->execute(
                "UPDATE approval_rules SET received_approvals = received_approvals + 1 "
                "WHERE id = ?", {ruleId}
            );
        } else {
            db->execute(
                "UPDATE approval_rules SET received_rejections = received_rejections + 1 "
                "WHERE id = ?", {ruleId}
            );
        }

        // 3. 합의 평가 (Phase A-9): 모드별 로직으로 최종 판정
        //   반환값: 'PENDING' (계속 진행), 'APPROVED' (승인 확정), 'REJECTED' (거절 확정)
        std::string consensusResult = evaluateConsensus(ruleId);

        if (consensusResult == "PENDING") {
            // 합의 미도달 → 다른 승인자 결정 대기. 상태 전이 없음.
            auditLog->logActivity(userId, fileId, "approval_decision_recorded",
                "User " + userId + " " + actionVerb + ", consensus pending");

            // 부분 결정도 요청자에게 알림 (진행 상황 공유)
            // 04/30 Phase A-3: 자동 트리거
            notifyStakeholders(fileId, "approval_progress",
                "Decision recorded by " + userId + " (" + actionVerb + "). Consensus pending.",
                {});
            return true;
        }

        // 4. 합의 도달 → 최종 상태로 전이
        //   consensusResult가 'APPROVED'면 newStatus 정보 그대로 사용 (호출자가 APPROVED 의도로 호출했고 합의도 일치)
        //   불일치 케이스 (호출자는 REJECT인데 합의는 APPROVED 등)는 발생할 수 없음:
        //     - 단일 결정으로 합의가 뒤집히지 않으므로
        //     - 호출자 의도와 합의 결과는 항상 일치
        //   다만 '거절 확정'이 호출자 의도가 APPROVE인 케이스에서도 발생 가능
        //     예: THRESHOLD 5/3에서 본인이 APPROVE했지만 이미 3명이 REJECT한 상황
        //   이 경우 상태는 REJECTED로 전이되어야 함
        DocumentStatus finalStatus;
        const char* finalActionTag;
        std::string finalActionVerb;
        if (consensusResult == "APPROVED") {
            finalStatus = DocumentStatus::APPROVED;
            finalActionTag = TAG_APPROVED;
            finalActionVerb = "approved";
        } else {  // "REJECTED"
            finalStatus = DocumentStatus::REJECTED;
            finalActionTag = TAG_REJECTED;
            finalActionVerb = "rejected";
        }

        // 5. 상태 태그 변경
        // 03/18 - setDocumentStatus 반환값 검사: 전이 실패 시 즉시 실패 반환
        std::string statusComment = static_cast<char>(std::toupper(
            static_cast<unsigned char>(finalActionVerb[0])))
            + finalActionVerb.substr(1) + " (consensus reached): " + comment;

        if (!setDocumentStatus(userId, fileId, finalStatus, statusComment)) {
            auditLog->logActivity(userId, fileId, "approval_" + finalActionVerb + "_failed",
                                "Status transition to " + std::string(finalActionTag) + " failed");
            return false;
        }

        // 6. 요청자에게 최종 알림
        // 05/14 - 알림 발송 경로 일관성 수정
        //   문제: notificationService->sendNotification 직접 호출이 Outbox/dedup을 우회
        //         (A-X에서 도입한 신뢰성 인프라가 이 경로에만 적용 안 됨)
        //   해결: notifyStakeholders로 통일. 요청자만 정확히 지정하기 위해 명시 targets 사용
        //   broadcast(8단계)와 분리 유지: broadcast는 generic 메시지, 여기는 personalized
        auto requester = db->query(
            "SELECT entity_id FROM approval_rule_requesters WHERE rule_id = ?",
            {ruleId}
        );
        if (!requester.empty()) {
            std::string finalSubject = (consensusResult == "APPROVED")
                ? "Your document was approved"
                : "Your document was rejected";
            std::string notifyBody = "File " + fileId + " has been " + finalActionVerb +
                                     " (consensus reached, last decision by " + userId + ")";
            // 03/26 - 회의 결정: APPROVE에도 comment 포함
            if (!comment.empty()) {
                notifyBody += ". Comment: " + comment;
            }
            // 05/14 - sendNotification 직접 호출 → notifyStakeholders 통합 (Outbox 적용)
            std::vector<NotificationTarget> requesterTarget = {{
                requester[0]["entity_id"],
                {NotificationChannel::PUSH, NotificationChannel::EMAIL, NotificationChannel::WEB}
            }};
            notifyStakeholders(fileId, "approval_completed",
                finalSubject + ": " + notifyBody,
                requesterTarget);
        }

        // 7. 승인 규칙 종료 (OPEN → CLOSED)
        // 03/18 - stale rule 방지
        db->execute("UPDATE approval_rules SET status = 'CLOSED' WHERE id = ?",
                    {ruleId});

        // 8. 다른 이해관계자에게 broadcast 알림 (Phase A-3 자동 트리거)
        // 04/30 - 위 6단계의 요청자 1대1 알림과는 별도
        std::string requesterId = requester.empty() ? "" : requester[0]["entity_id"];
        auto stakeholders = getDefaultStakeholders(fileId, "approval_completed");
        std::vector<NotificationTarget> broadcastTargets;
        for (const auto& t : stakeholders) {
            if (t.userId != requesterId && t.userId != userId) {  // 요청자/결정자 제외
                broadcastTargets.push_back(t);
            }
        }
        if (!broadcastTargets.empty()) {
            notifyStakeholders(fileId, "approval_completed",
                "File " + fileId + " was " + finalActionVerb + " by consensus",
                broadcastTargets);
        }

        return true;
    }

    // ============================================================
    // RD-SRS-9.7 합의 평가 헬퍼 메서드 (Phase A-9, 05/06 추가)
    // ------------------------------------------------------------

    // 합의 모델 enum → 문자열 변환 (DB 저장용)
    static std::string consensusModeToString(ApprovalConsensusMode m) {
        switch (m) {
            case ApprovalConsensusMode::THRESHOLD:  return "THRESHOLD";
            case ApprovalConsensusMode::UNANIMOUS:  return "UNANIMOUS";
            case ApprovalConsensusMode::SEQUENTIAL: return "SEQUENTIAL";
        }
        return "THRESHOLD";  // 안전 기본값
    }

    // SEQUENTIAL 모드에서 본인 차례인지 확인 (결정 ② C 채택)
    //   THRESHOLD/UNANIMOUS는 항상 true 반환 (순서 무관)
    //   SEQUENTIAL은 본인보다 앞 순서가 모두 결정된 경우만 true
    bool isUserTurnInSequence(const std::string& ruleId, const std::string& userId) {
        // 모드 조회
        auto modeRows = db->query(
            "SELECT consensus_mode FROM approval_rules WHERE id = ?", {ruleId}
        );
        if (modeRows.empty()) return false;
        std::string mode = modeRows[0].at("consensus_mode");

        if (mode != "SEQUENTIAL") {
            return true;  // 순서 무관 모드
        }

        // 본인의 sequence_order 조회
        auto myOrder = db->query(
            "SELECT sequence_order FROM approval_rule_approvers "
            "WHERE rule_id = ? AND entity_id = ? LIMIT 1",
            {ruleId, userId}
        );
        if (myOrder.empty()) return false;
        int mySeq = std::stoi(myOrder[0].at("sequence_order"));

        // 본인 앞 순서의 승인자가 모두 결정했는지 확인
        auto pendingBefore = db->query(
            "SELECT a.entity_id FROM approval_rule_approvers a "
            "WHERE a.rule_id = ? AND a.sequence_order < ? "
            "  AND a.entity_id NOT IN ("
            "    SELECT user_id FROM approval_activity "
            "    WHERE rule_id = ? AND action IN ('approved', 'rejected')"
            "  )",
            {ruleId, std::to_string(mySeq), ruleId}
        );

        return pendingBefore.empty();  // 앞 순서가 모두 결정 완료 시 본인 차례
    }

    // 합의 평가 (결정 ① D, 결정 ② C 채택)
    //   반환: "PENDING" / "APPROVED" / "REJECTED"
    //   THRESHOLD : received_approvals >= required_approvals → APPROVED
    //               received_rejections > (총 승인자 수 - required_approvals) → REJECTED
    //                  (남은 승인자가 모두 APPROVE해도 임계값 도달 불가능)
    //   UNANIMOUS : 한 명이라도 REJECT → REJECTED 즉시
    //               received_approvals == 총 승인자 수 → APPROVED
    //   SEQUENTIAL: 한 명이라도 REJECT → REJECTED 즉시 (앞 단계에서 실패하면 뒤도 안 봄)
    //               received_approvals == 총 승인자 수 → APPROVED
    std::string evaluateConsensus(const std::string& ruleId) {
        // 규칙 정보 조회
        auto ruleRows = db->query(
            "SELECT consensus_mode, required_approvals, "
            "       received_approvals, received_rejections "
            "FROM approval_rules WHERE id = ?", {ruleId}
        );
        if (ruleRows.empty()) return "PENDING";  // 안전 기본값

        std::string mode      = ruleRows[0].at("consensus_mode");
        int required          = std::stoi(ruleRows[0].at("required_approvals"));
        int receivedApprovals = std::stoi(ruleRows[0].at("received_approvals"));
        int receivedRejections= std::stoi(ruleRows[0].at("received_rejections"));

        // 전체 승인자 수 조회
        auto totalRows = db->query(
            "SELECT COUNT(*) AS cnt FROM approval_rule_approvers WHERE rule_id = ?",
            {ruleId}
        );
        int totalApprovers = totalRows.empty() ? 0 : std::stoi(totalRows[0].at("cnt"));

        if (mode == "THRESHOLD") {
            // 승인 임계값 도달
            if (receivedApprovals >= required) return "APPROVED";
            // 임계값 도달 불가능: 남은 승인자가 모두 APPROVE해도 부족한 경우
            //   남은 승인자 수 = totalApprovers - receivedApprovals - receivedRejections
            //   이 값을 더해도 임계값 미달 → 즉시 REJECTED
            int remaining = totalApprovers - receivedApprovals - receivedRejections;
            if (receivedApprovals + remaining < required) return "REJECTED";
            return "PENDING";
        } else if (mode == "UNANIMOUS") {
            if (receivedRejections > 0) return "REJECTED";  // 한 명이라도 거절 → 즉시 종료
            if (receivedApprovals == totalApprovers) return "APPROVED";
            return "PENDING";
        } else {  // SEQUENTIAL
            if (receivedRejections > 0) return "REJECTED";  // 한 명이라도 거절 → 즉시 종료
            if (receivedApprovals == totalApprovers) return "APPROVED";
            return "PENDING";
        }
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
    // 05/06 - Phase A-8: 매트릭스 확정 (4월 합의 사항 반영)
    //   결정 ① UNDER_REVIEW → DRAFT 허용 (CANCEL 흐름 정상 동작 보장, A-7 후속)
    //   결정 ② DEPRECATED → DRAFT 일반 불허, 관리자 권한으로만 (별도 메서드 restoreFromDeprecated)
    //   결정 ③ APPROVED → DRAFT 일반 불허, 오류 수정 한정 허용 (별도 메서드 revertApprovedToDraft)
    //   결정 ④ APPROVED → UNDER_REVIEW 불허 (재승인은 새 버전으로)
    //   결정 ⑤ REJECTED → DEPRECATED 허용 (포기 시나리오)
    //   결정 ⑥ stateTransitionConfig 객체로 커스터마이징 가능
    //
    // 도입 기업 커스터마이징:
    //   StateTransitionConfig 객체를 setTransitionMatrix()로 채워주면 우선 사용.
    //   미주입 시 기본 매트릭스 사용 (아래 default).
    //
    // 매트릭스:
    //   (없음)        → DRAFT, UNDER_REVIEW                새 파일 최초 상태 설정
    //   DRAFT         → UNDER_REVIEW, DEPRECATED            검토 요청 또는 폐기
    //   UNDER_REVIEW  → APPROVED, REJECTED, DRAFT           결정 또는 CANCEL 복귀
    //   APPROVED      → DEPRECATED                          승인 후 폐기만 일반 허용
    //   REJECTED      → DRAFT, DEPRECATED                   재작업 또는 포기
    //   DEPRECATED    → (전이 불가)                         관리자 메서드로만 복원
    bool isValidTransition(const std::string& currentTag, const std::string& newTag) {
        // 05/06 - 커스터마이징 매트릭스 우선 사용 (결정 ⑥)
        if (stateTransitionConfig != nullptr && !stateTransitionConfig->isEmpty()) {
            const auto& custom = stateTransitionConfig->matrix;
            auto it = custom.find(currentTag);
            if (it == custom.end()) return false;
            return std::find(it->second.begin(), it->second.end(), newTag) != it->second.end();
        }

        // 기본 매트릭스 (Phase A-8 결정 사항 반영)
        static const std::map<std::string, std::vector<std::string>> transitionMatrix = {
            {"",                {TAG_DRAFT, TAG_UNDER_REVIEW}},                    // 새 파일
            {TAG_DRAFT,         {TAG_UNDER_REVIEW, TAG_DEPRECATED}},               // 초안
            {TAG_UNDER_REVIEW,  {TAG_APPROVED, TAG_REJECTED, TAG_DRAFT}},          // 결정 ①: CANCEL 복귀
            {TAG_APPROVED,      {TAG_DEPRECATED}},                                 // 승인됨 (DRAFT는 별도 메서드)
            {TAG_REJECTED,      {TAG_DRAFT, TAG_DEPRECATED}},                      // 결정 ⑤: 포기 허용
            {TAG_DEPRECATED,    {}},                                               // 폐기 (관리자 별도 메서드로만 복원)
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