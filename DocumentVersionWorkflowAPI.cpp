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
#include <functional> // std::hash, std::function 사용을 위해
#include <unordered_set> // 삭제 대상 중복 체크 O(1)을 위해
#include <unordered_map> // applyVersionRetentionPolicy의 versionId→storage_key 매핑용
#include <iomanip>       // std::setfill, std::setw (SHA-256 hex 출력용)
#include <cstring>       // memset (DatabaseConnection Prepared Statement 바인딩용)
#include <fstream>       // FileStorage 로컬 파일 I/O용
#include <filesystem>    // FileStorage 디렉터리 생성용 (C++17)
#include <mariadb/mysql.h> // MariaDB Connector/C (DatabaseConnection용)
                           // 설치: sudo apt install libmariadb-dev
                           //       또는 sudo yum install mariadb-devel
                           // 빌드: g++ -std=c++17 ... -lmariadb -lstdc++fs

// 데이터 구조체 정의

// 파일 콘텐츠 및 메타데이터
struct FileContent {
    std::vector<uint8_t> data;
    std::string mimeType;
    // size 멤버 변수를 메서드로 변환
    // 이유: data를 resize한 뒤 size를 갱신하지 않으면 DB에 잘못된 크기가 기록됨
    //       size() 메서드는 항상 data.size()를 반환하므로 불일치 불가
    // 변경 영향: content.size → content.size() (6곳)
    size_t size() const { return data.size(); }
};

// 버전 레코드 정보
// UUID + revision_no + storage_key 구조로 보강
//   기존: versionId 하나가 "내부 식별자 + 사용자 표시 + 저장 위치"를 모두 의미
//   변경: 세 가지 역할을 별도 필드로 분리
//     - versionId   : 내부 식별자 (UUID). 외부 노출/FK용. 의미 없음(=보안상 좋은 성질)
//     - revisionNo  : 사용자에게 보이는 버전 번호 (1, 2, 3 ...). 파일별로 단조 증가
//     - storageKey  : 실제 버전 스냅샷 파일의 저장 위치. versionId 문자열로 경로를 추론하지 않음
//   배경: 기존 "{fileId}.v{ts}_{counter}" 방식은 fileId/timestamp/저장경로/순서를
//        한 문자열에 묶어버려, 어느 한 축이 바뀌면 전체가 깨지는 구조였음.
//        세 필드로 분리하여 각각 독립적으로 변경/조회 가능하도록 함.
struct VersionInfo {
    std::string versionId;      // 버전 고유 ID (UUID, CHAR(36)). 내부 식별자
    std::string fileId;         // 문서 고유 ID (UUID, CHAR(36)). documents.file_id 참조
    long long revisionNo;       // 05/18 추가: 사용자 표시용 버전 번호 (1, 2, 3 ...).
                                //             파일 내에서 UNIQUE (files_versions.uq_file_revision)
    std::string userId;         // 버전 생성자
    int64_t timestamp;          // 생성 시각 (Unix timestamp, 초 단위)
    size_t size;                // 파일 크기 (bytes)
    std::string mimeType;       // MIME 타입
    std::string storageKey;     // 05/18 추가: 실제 버전 파일의 저장 위치.
                                //             형식 예: "objects/{fileId}/versions/{versionId}"
                                //             versionId 문자열에서 경로를 조립하지 말고 이 필드를 사용
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

// DiffService 관련 코드를 헤더 파일로 분리
// DiffLineType, DiffLine, DiffHunk, DiffMethod, DiffResult,
// DocumentType, DocumentTextExtractor, DiffService 클래스가 포함됨
// 주의: FileContent 구조체가 위에 정의된 후에 include해야 함
#include "Diffservice.h"

// 버전 비교용 콘텐츠 쌍
// DiffResult 필드 추가: 서버 측에서 계산된 diff 결과를 포함
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
    CANCEL          // Phase A-7: 승인 요청 취소 (요청자/승인자/관리자)
                    // REJECT와의 차이:
                    //   - REJECT: "검토했고 부적합" → 거절 사유와 함께 이력 보존
                    //   - CANCEL: "이 요청 자체가 무효, 처리 안 함" → 행정적 무효화
};

// 다수 승인자 합의 모델 (결정 ① D 채택)
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

// 알림 채널 enum 전환
//   문제: 오타를 컴파일러가 못 잡음, 타입 안정성 없음
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

// 보존 정책 적용 범위 (결정 ① C 채택)
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

// (결정 ⑥): 상태 전이 매트릭스 커스터마이징 설정
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

// ============================================================
// RD-SRS-9.3: 통합 변경 이력 엔트리 구조체
// ------------------------------------------------------------
// getDocumentHistory()의 반환 타입.
// files_versions, activity, approval_activity 세 소스에서 수집한
// 변경 이력을 통일된 형태로 표현한다.
// [Java 전환 시] DocumentHistoryDto 또는 HistoryEntry 레코드 클래스로 변환.
// ============================================================
struct HistoryEntry {
    std::string source;      // 이력 출처: "versions" | "activity" | "approval"
    std::string userId;      // 변경 수행자 ID
    int64_t     timestamp;   // Unix timestamp (초 단위)
    std::string action;      // 변경 유형: "version_created", "version_updated",
                             //            "status_changed", "approval_approved" 등
    std::string revisionNo;  // 버전 번호 (versions 소스일 때만, 그 외 "")
    std::string versionId;   // 버전 UUID (versions/activity 소스, 그 외 "")
    std::string summary;     // 변경 내용 요약 (diff summary 또는 action 설명)
    std::string reason;      // 변경 사유 (입력된 경우만, 그 외 "")
};

// ============================================================
// Prototype Dependency Stubs
// ============================================================
// 아래 클래스들은 C++ 프로토타입에서 실제 구현을 생략한 외부 의존성이다.
// 본 파일의 목적은 문서 버전 관리, 승인 워크플로우, 상태 전이,
// 알림 대상 계산, 보존 정책 등 핵심 비즈니스 흐름을 검증하는 것이며,
// DB 연결, 파일 저장소, 알림 발송, 감사 로그 저장 같은 인프라 구현은
// Java/Spring 전환 단계에서 별도 계층으로 구현한다.
// Java 전환 시 예상 분리:
// - DatabaseConnection     → Repository / JdbcTemplate / JPA
// - FileStorage            → FileStorage 인터페이스 + 구현체
// - AuditLogService        → AuditLogService + AuditLogRepository
// - WorkflowEngine         → Spring Event / WorkflowService / RuleEngine
// - NotificationService    → NotificationService + Outbox + Sender
// - VersionService         → DocumentVersionService
// - DocumentStatusManager  → DocumentStatusService
// - PolicyManager          → RetentionPolicyService
// 주의:
// 현재 stub 메서드는 실제 부작용(side effect)을 수행하지 않는다.
// 따라서 C++ 단계의 실행 결과는 실제 DB/파일/알림 동작을 검증하지 못하며,
// 메서드 호출 순서와 비즈니스 로직 설계 검토 용도로만 사용한다.

// VersionService
// ------------------------------------------------------------
// Prototype stub:
//   외부 의존성을 표현하기 위한 자리표시자. 메서드는 실제 부작용을 수행하지 않으며,
//   호출 흐름과 의존성 구조를 보여주기 위한 용도다.
// [현재 상태]
// C++ 프로토타입에서는 실제 구현하지 않은 stub 클래스.
// 현재 버전 생성/수정/조회/삭제 로직은 DocumentVersionWorkflowAPI 내부의
// createInitialVersion(), onDocumentModified(), getVersionsAtTime(),
// deleteVersion(), applyVersionRetentionPolicy() 등에 직접 작성되어 있음.
// [역할]
// Java/Spring 전환 시 문서 버전 관리 비즈니스 로직을 담당할 Service 계층.
// - 최초 버전 생성
// - 문서 수정 시 자동 버전 생성
// - 특정 시점 버전 조회
// - 버전 삭제
// - 버전 보존 정책 적용
// [Java 전환 시]
// DocumentVersionService 또는 VersionService로 분리하고,
// Controller는 이 서비스를 호출하며,
// DB 접근은 VersionRepository/JdbcTemplate/JPA Repository로 분리한다.
class VersionService {};

// AuditLogService
// ------------------------------------------------------------
// Prototype stub:
//   감사 로그 기록 시점을 표시하기 위한 자리표시자. 실제 로그 저장은 수행하지 않으며,
//   호출 흐름과 의존성 구조를 보여주기 위한 용도다.
// [현재 상태]
// C++ 프로토타입에서는 실제 로그 저장을 수행하지 않는 stub.
// logActivity()는 호출 위치를 표시하기 위한 no-op 메서드임.
// [역할]
// 사용자/관리자의 주요 행위를 감사 로그로 기록하는 서비스.
// 예:
// - 버전 생성
// - 문서 수정
// - 상태 변경
// - 승인 요청/승인/거절
// - 정책 생성/수정/삭제
// - 알림 발송 실패
// [DB 연동]
// Java 전환 시 activity 테이블 또는 별도의 admin_audit_log 테이블에 기록.
// 현재 Schema.sql의 activity 테이블과 연결될 예정.
// [Java 전환 시]
// AuditLogService.record(...)
// AuditLogRepository.insert(...)
// 또는 Spring AOP를 이용한 감사 로그 자동 기록으로 확장 가능.
// DatabaseConnection 전방 선언 (AuditLogService에서 포인터 멤버로 사용)
class DatabaseConnection;

// AuditLogService
// ------------------------------------------------------------
// activity 테이블에 감사 로그를 실제로 INSERT하는 구현체.
// DatabaseConnection을 주입받아 동작한다.
// 파라미터:
//   userId   - 활동 수행자 ID
//   fileId   - 대상 파일 ID (파일과 무관한 활동이면 빈 문자열)
//   action   - 활동 유형 (예: "version_created", "status_changed")
//   message  - 활동 상세 메시지
// [전환 시]
// 별도 AuditLogService 빈으로 분리하고,
// AOP 또는 이벤트 리스너를 통해 자동 기록하도록 확장 가능.
class AuditLogService {
public:
    // DatabaseConnection은 외부에서 주입받음
    // (DocumentVersionWorkflowAPI 생성자에서 db와 동일한 연결 인스턴스 사용)
    DatabaseConnection* db = nullptr;

    // 구현은 DatabaseConnection 정의 이후에 위치 (아래 auditLogImpl 참고)
    void logActivity(const std::string& userId,
                     const std::string& fileId,
                     const std::string& action,
                     const std::string& message);
};

// DocumentStatusManager
// ------------------------------------------------------------
// Prototype stub:
//   문서 상태 관리 의존성을 표현하기 위한 자리표시자. 실제 상태 저장은 수행하지 않으며,
//   호출 흐름과 의존성 구조를 보여주기 위한 용도다.
// [현재 상태]
// C++ 프로토타입에서는 구현하지 않은 stub.
// 현재 문서 상태 관리는 DocumentVersionWorkflowAPI 내부의
// setDocumentStatus(), getCurrentStatusTag(), isValidTransition()
// 메서드가 직접 처리하고 있음.
// [역할]
// 문서의 상태값을 관리하는 서비스.
// 상태 예:
// - draft
// - under_review
// - approved
// - rejected
// - deprecated
// [관련 요구사항]
// RD-SRS-9.6: 문서 상태(초안, 검토 중, 승인됨, 폐기됨 등) 관리.
// [Java 전환 시]
// DocumentStatusService로 분리하고,
// 상태 전이 검증은 StateTransitionPolicy 또는 StateTransitionConfig로 분리.
// DB 접근은 systemtag, systemtag_object_mapping Repository에서 처리.
class DocumentStatusManager {};

// WorkflowEngine
// ------------------------------------------------------------
// Prototype stub:
//   이벤트 디스패치/룰 평가 의존성을 표현하기 위한 자리표시자. 실제 이벤트 처리나
//   룰 평가는 수행하지 않으며, 호출 흐름과 의존성 구조를 보여주기 위한 용도다.
// [현재 상태]
// C++ 프로토타입에서는 실제 이벤트 처리/룰 평가를 수행하지 않는 stub.
// dispatchEvent(), evaluateRules()는 이벤트 발생 지점을 표시하기 위한 no-op 메서드임.
// [역할]
// 문서 변경, 상태 변경, 승인 완료 등의 이벤트를 받아
// 사전에 정의된 워크플로우 규칙을 평가하고 후속 작업을 실행한다.
// 예:
// - version_updated 이벤트 발생 시 이해관계자 알림
// - tag_assigned 이벤트 발생 시 상태별 정책 적용
// - document_approved 이벤트 발생 시 문서 상태 approved 전환
// - document_rejected 이벤트 발생 시 요청자에게 재작업 알림
// [Java 전환 시]
// Spring ApplicationEventPublisher + @EventListener,
// 또는 별도 WorkflowService/RuleEngine으로 구현.
// 복잡한 운영 환경에서는 Camunda, Flowable 같은 BPMN 엔진 연동도 가능.
// WorkflowEngine
// ------------------------------------------------------------
// Observer 패턴 기반 이벤트 디스패치 구현체.
// 사용 방법:
//   // 리스너 등록
//   workflowEngine->on("version_updated", [](auto& event, auto& data) {
//       // 처리 로직
//   });
//   // 이벤트 발행
//   workflowEngine->dispatchEvent("version_updated", {{"fileId", id}});
// 이벤트 종류:
//   - "version_updated"    : 버전 생성/수정 완료 → 알림 트리거
//   - "file_activity"      : 감사 로그 연동 이벤트
//   - "tag_assigned"       : 상태 태그 변경 → 정책 평가 트리거
//   - "document_approved"  : 승인 완료 → 후속 워크플로우 처리
// [전환 시]
// 이벤트 발행 메커니즘(ApplicationEventPublisher 등)으로 대체.
// dispatchEvent / evaluateRules 호출부는 변경 불필요.
class WorkflowEngine {
public:
    using EventData    = std::map<std::string, std::string>;
    using EventHandler = std::function<void(const std::string&, const EventData&)>;

    // 이벤트 리스너 등록
    void on(const std::string& eventName, EventHandler handler) {
        listeners_[eventName].push_back(std::move(handler));
    }

    // 이벤트 발행: 등록된 모든 리스너를 순서대로 호출
    void dispatchEvent(const std::string& eventName, const EventData& data) {
        auto it = listeners_.find(eventName);
        if (it == listeners_.end()) return;
        for (auto& handler : it->second) {
            try {
                handler(eventName, data);
            } catch (const std::exception& e) {
                // 리스너 예외가 발행 흐름을 중단시키지 않도록 처리
                std::cerr << "[WorkflowEngine] 리스너 예외 ("
                          << eventName << "): " << e.what() << std::endl;
            }
        }
    }

    // 룰 평가: 현재는 dispatchEvent와 동일하게 동작.
    // 향후 룰 엔진(조건 평가 → 후속 액션 결정) 도입 시 이 메서드를 확장.
    void evaluateRules(const std::string& eventName, const EventData& data) {
        dispatchEvent(eventName, data);
    }

private:
    std::unordered_map<std::string, std::vector<EventHandler>> listeners_;
};

// NotificationService
// ------------------------------------------------------------
// Prototype stub:
//   알림 발송 채널(이메일/푸시/웹) 의존성을 표현하기 위한 자리표시자. 실제 발송은
//   수행하지 않으며, 호출 흐름과 의존성 구조를 보여주기 위한 용도다.
// [현재 상태]
// C++ 프로토타입에서는 실제 이메일/푸시/웹 알림 발송을 수행하지 않는 stub.
// 현재 알림 대상 계산, 중복 방지, Outbox 저장 등은
// DocumentVersionWorkflowAPI 내부의 notifyStakeholders(),
// enqueueOutbox(), processOutboxQueue(), attemptDelivery()에 작성되어 있음.
// [역할]
// 실제 알림 채널로 메시지를 발송하는 외부 어댑터.
// - WEB: DB notifications 테이블에 저장
// - EMAIL: SMTP 또는 외부 메일 API 연동
// - PUSH: 모바일/데스크톱 푸시 토큰 기반 발송
// [Java 전환 시]
// NotificationService는 알림 비즈니스 로직,
// NotificationSender는 채널별 발송 어댑터로 분리하는 것이 바람직함.
// 예:
// - WebNotificationSender
// - EmailNotificationSender
// - PushNotificationSender
class NotificationService { 
public: 
    void notifyFileSubscribers(const std::string& f, const std::string& m, const std::string& u) {} 
    void sendNotification(const std::string& u, const std::string& s,
                          const std::string& m, const std::vector<NotificationChannel>& c) {} 
};

// PolicyManager
// ------------------------------------------------------------
// Prototype stub:
//   보존 정책 적용 위치를 표시하기 위한 자리표시자. 실제 정책 적용은
//   DocumentVersionWorkflowAPI::applyVersionRetentionPolicy()가 담당하며,
//   본 클래스의 메서드는 호환성 유지를 위한 no-op이다.
// [현재 상태]
// C++ 프로토타입 초기에 보존 정책 적용 위치를 표시하기 위해 만든 stub.
// 현재 실제 보존 정책 로직은 DocumentVersionWorkflowAPI 내부의
// createRetentionPolicy(), evaluatePolicy(),
// applyVersionRetentionPolicy(), applyToAllFiles() 등에 구현되어 있음.
// [역할]
// 버전 보존 정책을 평가하고 오래된 버전을 정리하는 서비스.
// - 최대 보관 일수
// - 최소 보관 일수
// - 최대 버전 수
// - 파일/폴더/사용자/전역 정책 우선순위
// [Java 전환 시]
// RetentionPolicyService 또는 PolicyService로 분리.
// @Scheduled 작업으로 주기적 정리 수행.
// DB 접근은 retention_policies Repository에서 처리.
// [주의]
// 현재 applyRetentionPolicy() stub은 실제 정리 작업을 수행하지 않으므로,
// C++ 프로토타입에서는 applyVersionRetentionPolicy()가 실질적인 구현이다.
class PolicyManager { 
public: 
    void applyRetentionPolicy(const std::string& f) {} 
};

// FileStorage
// ------------------------------------------------------------
// Prototype stub:
//   외부 파일 저장소(로컬 FS, WebDAV, S3, 암호화 저장소 등) 의존성을 표현하기 위한
//   자리표시자. 실제 파일 I/O는 수행하지 않으며, createInitialVersion(),
//   onDocumentModified() 등의 호출 흐름과 storage_key 기반 경로 규약을 검증하기
//   위한 용도다.
// [현재 상태]
// C++ 프로토타입에서는 실제 파일 I/O를 수행하지 않는 stub.
// 버전 생성/수정/복원 흐름을 검증하기 위해 메서드 시그니처만 제공한다.
// [역할]
// 중앙화 문서 저장소에 파일을 저장, 조회, 복사, 삭제하는 어댑터.
// - 원본 파일 저장
// - 버전 스냅샷 저장
// - 특정 버전 파일 읽기
// - 오래된 버전 파일 삭제
// [실제 구현 시 고려사항]
// - 로컬 파일시스템, Nextcloud WebDAV, S3, NAS, 암호화 저장소 중 선택 필요
// - 파일 저장과 DB 저장 간 정합성 보장 필요
// - 임시 파일 저장 후 commit/rollback 유사 처리 필요
// - 민감 문서의 경우 저장 시 암호화 필요
// [Java 전환 시]
// FileStorage 인터페이스를 만들고 구현체를 분리.
// 예:
// - LocalFileStorage
// - WebDavFileStorage
// - S3FileStorage
// - EncryptedFileStorage
// FileStorage
// ------------------------------------------------------------
// 로컬 파일시스템 기반 파일 I/O 구현체 (fstream + C++17 filesystem).
// storage_key를 상대 경로로 해석하여 BASE_PATH 아래에 저장한다.
// [저장 경로 규약]
//   BASE_PATH / objects / {fileId} / versions / {versionId}
//   예: ./storage/objects/abc-123/versions/def-456
// [배포 환경 설정]
//   BASE_PATH를 실제 저장 경로로 변경할 것.
//   프로토타입에서는 실행 디렉터리 기준 "./storage"를 기본값으로 사용.
// [전환 시]
// FileStorage 인터페이스를 유지한 채 WebDAV / S3 / NAS 구현체로 교체.
// 비즈니스 로직의 fileStorage->writeFile/readFile 호출부는 변경 불필요.
class FileStorage {
public:
    // 파일 저장 기본 경로 (배포 환경에 맞게 수정)
    std::string BASE_PATH = "./storage";  // 예: "/var/nextcloud/data"

    // storage_key → 실제 파일 경로 조합
    std::string resolvePath(const std::string& storageKey) const {
        return BASE_PATH + "/" + storageKey;
    }

    // Deprecated: filePath 기반 fileId 생성.
    //   새 흐름에서는 generateUUID()를 사용하므로 호출되지 않음.
    //   호환성을 위해 시그니처만 유지.
    std::string generateFileId(const std::string& p) { return "file_id_" + p; }

    // 파일 쓰기: storageKey 경로에 content.data를 저장.
    // 디렉터리가 없으면 자동 생성.
    void writeFile(const std::string& storageKey, const FileContent& content) {
        std::string path = resolvePath(storageKey);
        std::filesystem::path p(path);
        std::filesystem::create_directories(p.parent_path());

        std::ofstream ofs(path, std::ios::binary | std::ios::trunc);
        if (!ofs) {
            throw std::runtime_error("파일 열기 실패: " + path);
        }
        if (!content.data.empty()) {
            ofs.write(reinterpret_cast<const char*>(content.data.data()),
                      static_cast<std::streamsize>(content.data.size()));
            // write() 이후 스트림 상태 검사 (디스크 부족, 권한 문제 등 감지)
            if (!ofs.good()) {
                ofs.close();
                std::filesystem::remove(path); // 부분 작성된 파일 제거
                throw std::runtime_error("파일 쓰기 중 오류 발생: " + path);
            }
        }
        ofs.close();
        // close/flush 실패 검사 (버퍼 flush 중 오류 감지)
        if (!ofs) {
            throw std::runtime_error("파일 close/flush 실패: " + path);
        }
    }

    // 파일 복사: src → dst 경로로 복사.
    // 버전 스냅샷 생성 시 사용 (onDocumentModified의 백업 단계).
    void copyFile(const std::string& srcKey, const std::string& dstKey) {
        std::string srcPath = resolvePath(srcKey);
        std::string dstPath = resolvePath(dstKey);
        std::filesystem::path dst(dstPath);
        std::filesystem::create_directories(dst.parent_path());
        std::filesystem::copy_file(srcPath, dstPath,
            std::filesystem::copy_options::overwrite_existing);
    }

    // 파일 읽기: storageKey 경로의 파일을 FileContent로 반환.
    // 파일이 없거나 열 수 없으면 예외를 던진다 (빈 FileContent 반환 아님).
    // 이유: 빈 반환은 "파일 없음"과 "실제 빈 파일"을 구분할 수 없어
    //       diff에서 "전체 내용이 추가됨"으로 잘못 해석될 수 있음.
    // 호출부(prepareVersionComparison 등)에서 catch하여 명확하게 실패 처리.
    FileContent readFile(const std::string& storageKey) {
        std::string path = resolvePath(storageKey);
        std::ifstream ifs(path, std::ios::binary);
        if (!ifs) {
            // 파일 없음을 빈 파일처럼 반환하면 diff에서 "전체 내용이 추가됨"으로
            // 잘못 해석될 수 있으므로 예외를 던진다.
            // 호출부(prepareVersionComparison 등)에서 catch하여 명확하게 실패 처리.
            throw std::runtime_error("파일을 열 수 없음: " + path);
        }
        FileContent fc;
        fc.data.assign(std::istreambuf_iterator<char>(ifs),
                       std::istreambuf_iterator<char>());
        return fc;
    }

    // 파일 삭제: storageKey 경로의 파일 삭제.
    // 파일이 없으면 조용히 무시 (보존 정책 적용 시 이미 삭제된 경우 대비).
    // 파일 삭제. 파일이 없으면 조용히 무시(정상 케이스).
    // 그 외 오류(권한 문제 등)는 예외를 던져 호출부가 인지할 수 있게 함.
    // [전환 시] 민감 문서 삭제 실패는 보안 이슈이므로 알림/로그 강화 필요
    void deleteFile(const std::string& storageKey) {
        std::string path = resolvePath(storageKey);
        std::error_code ec;
        bool removed = std::filesystem::remove(path, ec);
        if (!removed && ec) {
            // 파일 없음(not_found)은 정상, 그 외 오류는 예외
            if (ec != std::errc::no_such_file_or_directory) {
                throw std::runtime_error("파일 삭제 실패: " + path + " (" + ec.message() + ")");
            }
        }
    }
};

// DatabaseConnection
// ------------------------------------------------------------
// Prototype stub:
//   DB(MariaDB) 접근 의존성을 표현하기 위한 자리표시자. execute()와 query()는
//   실제 DB에 접근하지 않으며, 호출 흐름·SQL 문법·바인딩 파라미터 구조를
//   검증하기 위한 용도다. 실 운영에서는 connection pool과 트랜잭션 관리가
//   추가되어야 한다.
// [현재 상태]
// C++ 프로토타입에서는 실제 DB 연결을 수행하지 않는 stub.
// SQL 문과 파라미터 구조를 검증하기 위한 용도이며,
// execute()와 query()는 실제 MariaDB에 접근하지 않는다.
// [역할]
// 문서 버전, 상태 태그, 승인 규칙, 알림, 감사 로그, 보존 정책 등
// 모든 영속 데이터를 DB에 저장/조회하는 공통 데이터 접근 계층.
// [주의]
// 현재 execute()는 항상 0을 반환하므로,
// 영향받은 row 수를 기준으로 성공/실패를 판단하는 로직은
// 실제 실행 결과와 다르게 동작할 수 있다.
// 현재 query()는 항상 빈 결과를 반환하므로,
// 조회 기반 로직은 프로토타입 실행만으로 검증하기 어렵다.
// [Java 전환 시]
// Spring JdbcTemplate.update() / queryForList()
// 또는 JPA Repository로 대체.
// 트랜잭션은 @Transactional로 관리.
// SQL은 Repository 계층으로 이동.
// DatabaseConnection
// ------------------------------------------------------------
// MariaDB Connector/C 기반 실제 DB 연결 구현체.
// Prepared Statement를 사용하므로 SQL Injection에 안전하다.
// [접속 정보 설정]
// 아래 DB_HOST, DB_USER, DB_PASSWORD, DB_NAME 상수를 환경에 맞게 수정할 것.
// 프로토타입 단계에서는 빈칸으로 두며, 실제 배포 시 환경 변수 또는 설정 파일로 주입.
// [빌드]
// g++ -std=c++17 DocumentVersionWorkflowAPI.cpp -lmariadb -lstdc++fs -o prototype
// [전환 시]
// 이 클래스 전체를 DB 접근 라이브러리(JdbcTemplate 등)로 대체한다.
// 비즈니스 로직(db->execute / db->query 호출부)은 변경 불필요.
class DatabaseConnection {
public:
    // ── 접속 정보 (배포 환경에 맞게 수정)
    static constexpr const char* DB_HOST     = "";   // 예: "127.0.0.1"
    static constexpr const char* DB_USER     = "";   // 예: "nextcloud"
    static constexpr const char* DB_PASSWORD = "";   // 예: "password"
    static constexpr const char* DB_NAME     = "";   // 예: "nextcloud"
    static constexpr unsigned int DB_PORT    = 3306;

    DatabaseConnection() {
        conn_ = mysql_init(nullptr);
        if (!conn_) {
            throw std::runtime_error("mysql_init() 실패: 메모리 부족");
        }
        // 재연결 옵션 활성화 (장시간 유휴 연결 방어)
        my_bool reconnect = 1;
        mysql_options(conn_, MYSQL_OPT_RECONNECT, &reconnect);

        if (!mysql_real_connect(conn_,
                                DB_HOST, DB_USER, DB_PASSWORD, DB_NAME,
                                DB_PORT, nullptr, 0)) {
            std::string err = "MariaDB 연결 실패: ";
            err += mysql_error(conn_);
            mysql_close(conn_);
            conn_ = nullptr;
            throw std::runtime_error(err);
        }
        // 문자셋 UTF-8 강제 설정
        mysql_set_character_set(conn_, "utf8mb4");
    }

    ~DatabaseConnection() {
        if (conn_) {
            mysql_close(conn_);
            conn_ = nullptr;
        }
    }

    // INSERT / UPDATE / DELETE 실행. affected rows 반환.
    // 반환 타입 void → int (영향받은 row 수)
    // 전환 시: DB 접근 라이브러리의 update() 메서드와 동일한 의미
    int execute(const std::string& sql,
                const std::vector<std::string>& params) {
        if (!conn_) throw std::runtime_error("DB 미연결 상태");

        MYSQL_STMT* stmt = mysql_stmt_init(conn_);
        if (!stmt) throw std::runtime_error("mysql_stmt_init() 실패");

        if (mysql_stmt_prepare(stmt, sql.c_str(), (unsigned long)sql.size())) {
            std::string err = "Prepare 실패: ";
            err += mysql_stmt_error(stmt);
            mysql_stmt_close(stmt);
            throw std::runtime_error(err);
        }

        auto binds = makeBinds(params);
        if (!params.empty() && mysql_stmt_bind_param(stmt, binds.data())) {
            std::string err = "Bind 실패: ";
            err += mysql_stmt_error(stmt);
            mysql_stmt_close(stmt);
            throw std::runtime_error(err);
        }

        if (mysql_stmt_execute(stmt)) {
            std::string err = "Execute 실패: ";
            err += mysql_stmt_error(stmt);
            mysql_stmt_close(stmt);
            throw std::runtime_error(err);
        }

        int affected = (int)mysql_stmt_affected_rows(stmt);
        mysql_stmt_close(stmt);
        return affected;
    }

    // SELECT 실행. 결과를 vector<map<컬럼명, 값>> 형태로 반환.
    std::vector<std::map<std::string, std::string>>
    query(const std::string& sql,
          const std::vector<std::string>& params) {
        if (!conn_) throw std::runtime_error("DB 미연결 상태");

        MYSQL_STMT* stmt = mysql_stmt_init(conn_);
        if (!stmt) throw std::runtime_error("mysql_stmt_init() 실패");

        if (mysql_stmt_prepare(stmt, sql.c_str(), (unsigned long)sql.size())) {
            std::string err = "Prepare 실패: ";
            err += mysql_stmt_error(stmt);
            mysql_stmt_close(stmt);
            throw std::runtime_error(err);
        }

        // 입력 파라미터 바인딩
        auto binds = makeBinds(params);
        if (!params.empty() && mysql_stmt_bind_param(stmt, binds.data())) {
            std::string err = "Bind 실패: ";
            err += mysql_stmt_error(stmt);
            mysql_stmt_close(stmt);
            throw std::runtime_error(err);
        }

        if (mysql_stmt_execute(stmt)) {
            std::string err = "Execute 실패: ";
            err += mysql_stmt_error(stmt);
            mysql_stmt_close(stmt);
            throw std::runtime_error(err);
        }

        // 결과 메타데이터로 컬럼명 수집
        MYSQL_RES* meta = mysql_stmt_result_metadata(stmt);
        if (!meta) {
            mysql_stmt_close(stmt);
            return {};  // SELECT 결과 없음 (DDL 등)
        }

        unsigned int colCount = mysql_num_fields(meta);
        std::vector<std::string> colNames;
        MYSQL_FIELD* fields = mysql_fetch_fields(meta);
        for (unsigned int i = 0; i < colCount; ++i) {
            colNames.push_back(fields[i].name);
        }
        mysql_free_result(meta);

        // 결과 행 바인딩
        // 1단계: 초기 버퍼(4096)로 fetch → 잘린 경우 mysql_stmt_fetch_column()으로 재조회
        // 이유: hunks_json, payload, metadata 등은 4096바이트를 초과할 수 있으며,
        //       고정 버퍼로만 받으면 JSON이 잘려 파싱 실패가 발생함
        const size_t INIT_BUF_SIZE = 4096;
        std::vector<std::vector<char>> bufs(colCount, std::vector<char>(INIT_BUF_SIZE, 0));
        std::vector<unsigned long> lengths(colCount, 0);
        std::vector<my_bool> isNull(colCount, 0);
        std::vector<my_bool> isError(colCount, 0);
        std::vector<MYSQL_BIND> outBinds(colCount);
        memset(outBinds.data(), 0, sizeof(MYSQL_BIND) * colCount);

        for (unsigned int i = 0; i < colCount; ++i) {
            outBinds[i].buffer_type   = MYSQL_TYPE_STRING;
            outBinds[i].buffer        = bufs[i].data();
            outBinds[i].buffer_length = INIT_BUF_SIZE;
            outBinds[i].length        = &lengths[i];
            outBinds[i].is_null       = &isNull[i];
            outBinds[i].error         = &isError[i];
        }

        if (mysql_stmt_bind_result(stmt, outBinds.data())) {
            mysql_stmt_close(stmt);
            throw std::runtime_error("결과 바인딩 실패");
        }

        // store_result 실패 검사 (결과 버퍼링 실패 시 명확한 오류)
        if (mysql_stmt_store_result(stmt)) {
            std::string err = "mysql_stmt_store_result 실패: ";
            err += mysql_stmt_error(stmt);
            mysql_stmt_close(stmt);
            throw std::runtime_error(err);
        }

        std::vector<std::map<std::string, std::string>> rows;
        int fetchRet;
        while ((fetchRet = mysql_stmt_fetch(stmt)) == 0
               || fetchRet == MYSQL_DATA_TRUNCATED) {
            std::map<std::string, std::string> row;
            for (unsigned int i = 0; i < colCount; ++i) {
                if (isNull[i]) {
                    row[colNames[i]] = "";
                    continue;
                }
                // 잘림 감지: isError[i] 또는 실제 길이 > 버퍼 크기
                if (isError[i] || lengths[i] > INIT_BUF_SIZE) {
                    // 실제 길이만큼 버퍼 재할당 후 재조회
                    unsigned long actualLen = lengths[i];
                    std::vector<char> bigBuf(actualLen + 1, 0);
                    MYSQL_BIND refetch;
                    memset(&refetch, 0, sizeof(refetch));
                    refetch.buffer_type   = MYSQL_TYPE_STRING;
                    refetch.buffer        = bigBuf.data();
                    refetch.buffer_length = actualLen;
                    refetch.length        = &actualLen;
                    // 반환값 검사: 0 = 성공, 그 외 = 실패
                    if (mysql_stmt_fetch_column(stmt, &refetch, i, 0)) {
                        std::string err = "mysql_stmt_fetch_column 실패 (컬럼 ";
                        err += colNames[i] + "): ";
                        err += mysql_stmt_error(stmt);
                        mysql_stmt_close(stmt);
                        throw std::runtime_error(err);
                    }
                    row[colNames[i]] = std::string(bigBuf.data(), actualLen);
                } else {
                    row[colNames[i]] = std::string(bufs[i].data(), lengths[i]);
                }
            }
            rows.push_back(row);
        }

        // fetch 루프 종료 후 오류 여부 확인
        // 0 = 정상 종료, MYSQL_NO_DATA = 데이터 없음 (정상), 그 외 = 오류
        if (fetchRet != 0 && fetchRet != MYSQL_NO_DATA) {
            std::string err = "mysql_stmt_fetch 루프 오류: ";
            err += mysql_stmt_error(stmt);
            mysql_stmt_close(stmt);
            throw std::runtime_error(err);
        }

        mysql_stmt_close(stmt);
        return rows;
    }

    // ── 트랜잭션 제어
    // 여러 SQL을 하나의 원자적 작업으로 묶을 때 사용.
    // 직접 호출보다는 아래 TransactionGuard를 사용하는 것이 안전하다.
    void beginTransaction() { execute("START TRANSACTION", {}); }
    void commit()           { execute("COMMIT", {}); }
    void rollback()         { execute("ROLLBACK", {}); }

private:
    MYSQL* conn_ = nullptr;

    // params 벡터를 MYSQL_BIND 배열로 변환하는 헬퍼
    // 주의: 반환된 MYSQL_BIND는 params의 수명에 의존하므로
    //       execute()/query() 스택 프레임 내에서만 사용해야 함
    std::vector<MYSQL_BIND> makeBinds(const std::vector<std::string>& params) {
        std::vector<MYSQL_BIND> binds(params.size());
        memset(binds.data(), 0, sizeof(MYSQL_BIND) * params.size());
        for (size_t i = 0; i < params.size(); ++i) {
            binds[i].buffer_type   = MYSQL_TYPE_STRING;
            binds[i].buffer        = const_cast<char*>(params[i].c_str());
            binds[i].buffer_length = (unsigned long)params[i].size();
            binds[i].length        = &binds[i].buffer_length;
        }
        return binds;
    }
};

// ============================================================
// TransactionGuard
// ------------------------------------------------------------
// RAII 방식으로 트랜잭션을 관리한다.
// 스코프를 벗어날 때 commit()이 호출되지 않았으면 자동으로 rollback.
// 사용 예:
//   {
//       TransactionGuard tx(*db);
//       db->execute("INSERT ...", {...});
//       db->execute("UPDATE ...", {...});
//       tx.commit(); // 여기까지 도달해야 커밋
//   }  // 예외 발생 또는 commit 미호출 시 소멸자에서 rollback
// [전환 시] @Transactional 어노테이션으로 대체
// ============================================================
class TransactionGuard {
public:
    explicit TransactionGuard(DatabaseConnection& db) : db_(db), committed_(false) {
        db_.beginTransaction();
    }

    void commit() {
        db_.commit();
        committed_ = true;
    }

    ~TransactionGuard() {
        if (!committed_) {
            try {
                db_.rollback();
            } catch (const std::exception& e) {
                std::cerr << "[TransactionGuard] rollback 실패: " << e.what() << std::endl;
            }
        }
    }

    // 복사/이동 금지
    TransactionGuard(const TransactionGuard&) = delete;
    TransactionGuard& operator=(const TransactionGuard&) = delete;

private:
    DatabaseConnection& db_;
    bool committed_;
};

// AuditLogService::logActivity 구현 (DatabaseConnection 완전 정의 이후)
inline void AuditLogService::logActivity(const std::string& userId,
                                          const std::string& fileId,
                                          const std::string& action,
                                          const std::string& message) {
    if (!db) {
        std::cerr << "[AuditLog] " << action
                  << " | user=" << userId
                  << " | file=" << fileId
                  << " | " << message << std::endl;
        return;
    }
    auto now = std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::system_clock::now().time_since_epoch()
    ).count();
    try {
        db->execute(
            "INSERT INTO activity (`timestamp`, `user`, affecteduser, app, "
            "subject, subjectparams, file, object_type, object_id) "
            "VALUES (?, ?, ?, 'files', ?, ?, ?, 'files', ?)",
            {std::to_string(now), userId, userId,
             action, message,
             fileId.empty() ? "" : fileId,
             fileId.empty() ? "" : fileId}
        );
    } catch (const std::exception& e) {
        std::cerr << "[AuditLog] INSERT 실패: " << e.what() << std::endl;
    }
}

class DocumentVersionWorkflowAPI {
private:
    // 내부 서비스 컴포넌트 (unique_ptr: 소유권 명확화 + 예외 안전성)
    // 생성자 중간에서 예외 발생 시에도 이미 생성된 객체가 자동 해제됨
    std::unique_ptr<DatabaseConnection>    db;
    std::unique_ptr<AuditLogService>       auditLog;
    std::unique_ptr<VersionService>        versionService;
    std::unique_ptr<DocumentStatusManager> statusManager;
    std::unique_ptr<WorkflowEngine>        workflowEngine;
    std::unique_ptr<NotificationService>   notificationService;
    std::unique_ptr<PolicyManager>         policyManager;
    std::unique_ptr<FileStorage>           fileStorage;
    std::unique_ptr<DiffService>           diffService;
    StateTransitionConfig*                 stateTransitionConfig = nullptr; // 선택적 주입, 소유권 없음

public:
    // 생성자: 각 컴포넌트를 초기화하고 이벤트 리스너를 등록한다.
    // DB 접속 정보는 DatabaseConnection::DB_HOST 등 상수로 관리.
    // FileStorage 저장 경로는 FileStorage::BASE_PATH로 관리.
    // [전환 시] 의존성 주입(DI)으로 각 컴포넌트를 외부에서 주입받도록 변경.
    DocumentVersionWorkflowAPI() {
        // DB 연결 (접속 정보: DatabaseConnection 상수 참조)
        db = std::make_unique<DatabaseConnection>();

        // AuditLogService에 DB 주입 (db보다 나중에 생성하여 dangling pointer 방지)
        auditLog = std::make_unique<AuditLogService>();
        auditLog->db = db.get();

        // 나머지 컴포넌트 초기화
        versionService      = std::make_unique<VersionService>();
        statusManager       = std::make_unique<DocumentStatusManager>();
        workflowEngine      = std::make_unique<WorkflowEngine>();
        notificationService = std::make_unique<NotificationService>();
        policyManager       = std::make_unique<PolicyManager>();
        fileStorage         = std::make_unique<FileStorage>();
        diffService         = std::make_unique<DiffService>();

        // ── 이벤트 리스너 등록
        // "version_updated": 버전 생성/수정 완료 → 알림 트리거
        workflowEngine->on("version_updated",
            [this](const std::string&, const WorkflowEngine::EventData& data) {
                auto fileIt = data.find("fileId");
                auto evIt   = data.find("eventType");
                auto msgIt  = data.find("message");
                if (fileIt == data.end()) return;
                std::string eventType = (evIt  != data.end()) ? evIt->second  : "version_updated";
                std::string message   = (msgIt != data.end()) ? msgIt->second : "버전이 업데이트되었습니다.";
                notifyStakeholders(fileIt->second, eventType, message, {});
            });

        // "document_approved": 승인 완료 → 알림 트리거
        workflowEngine->on("document_approved",
            [this](const std::string&, const WorkflowEngine::EventData& data) {
                auto fileIt = data.find("fileId");
                auto msgIt  = data.find("message");
                if (fileIt == data.end()) return;
                std::string message = (msgIt != data.end()) ? msgIt->second : "문서가 승인되었습니다.";
                notifyStakeholders(fileIt->second, "document_approved", message, {});
            });
    }

    // 소멸자: unique_ptr이 선언 역순으로 자동 해제하므로 명시적 delete 불필요.
    // db가 auditLog보다 나중에 선언되어 있으므로, auditLog가 먼저 해제된 뒤 db가 해제됨.
    // 즉 auditLog->db dangling pointer 문제가 자연스럽게 해결됨.
    ~DocumentVersionWorkflowAPI() = default;

    // 복사/이동 금지 (DB 연결과 파일 상태가 공유되면 안 됨)
    DocumentVersionWorkflowAPI(const DocumentVersionWorkflowAPI&) = delete;
    DocumentVersionWorkflowAPI& operator=(const DocumentVersionWorkflowAPI&) = delete;

private:

    // 태그 이름 상수 정의 (하드코딩 방지)
    // setDocumentStatus, processApprovalWorkflow 등에서 공통 사용
    // 태그 이름 변경 시 이곳만 수정하면 전체 반영
    static constexpr const char* TAG_DRAFT = "draft";
    static constexpr const char* TAG_UNDER_REVIEW = "under_review";
    static constexpr const char* TAG_APPROVED = "approved";
    static constexpr const char* TAG_REJECTED = "rejected";
    static constexpr const char* TAG_DEPRECATED = "deprecated";

public:
    // RD-SRS-9.1: 모든 문서는 고유한 버전 번호를 가져야 함
    // 클라이언트에서 호출되는 부분: WebDAV PUT /remote.php/dav/files/{user}/{path}
    // ID 생성 정책 전면 개정:
    //   [기존 문제]
    //     fileId = "file_id_" + filePath           → 경로 변경 시 동일 문서 추적 불가
    //     versionId = fileId + ".v{ts}_{cnt}"      → fileId·timestamp·counter에 모두 종속,
    //                                                길이 가변, 카운터 동시성/재시작 취약,
    //                                                versionId 문자열에서 저장 경로를 추론
    //   [개정 후]
    //     fileId      = generateUUID()             → 문서의 평생 식별자 (이동/이름변경 무관)
    //     versionId   = generateUUID()             → 버전 한 개의 식별자 (의미 없음 = 보안상 좋음)
    //     revisionNo  = 1, 2, 3 ...                → 사용자에게 보이는 버전 번호
    //     storageKey  = "objects/{fileId}/versions/{versionId}"
    //                                              → 실제 저장 위치, versionId 문자열로 경로 추론 X
    // [Java 전환 시] @Transactional + PENDING→ACTIVE 상태 전이로 파일-DB 정합성 보장
    VersionInfo createInitialVersion(const std::string& userId,
                                    const std::string& filePath,
                                    const FileContent& content) {
        // 1. 문서/버전 UUID 발급 (DB 접근 전에 발급 가능 → 저장 경로 조립에 즉시 사용)
        //    05/18 - generateFileId(filePath) 호출 제거.
        //            filePath는 이제 documents.current_path 컬럼에만 저장되고,
        //            fileId는 경로와 완전히 독립적인 UUID가 됨.
        auto fileId    = generateUUID();
        auto versionId = generateUUID();
        long long revisionNo = 1;  // 최초 버전은 항상 1

        // 2. 생성 시각
        // timestamp 문제: 초 단위로 통일
        // 더 이상 versionId 생성에 사용되지 않음. DB 컬럼 값으로만 사용.
        auto timestamp = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();

        // 3. 실제 저장 경로 (storage_key) 조립
        //    05/18 - 사용자 경로(filePath)와 내부 저장 경로를 분리.
        //            filePath는 사람이 보는 경로, storageKey는 storage 백엔드 내 위치.
        //            형식 규약은 storage 백엔드별로 다를 수 있으나, 의사코드에서는
        //            "objects/{fileId}/versions/{versionId}" 단일 규약을 가정한다.
        std::string storageKey = "objects/" + fileId + "/versions/" + versionId;

        // 4. 버전 스냅샷 저장 (maps to OC\Files\Node\File::putContent
        //                       + OCA\Files_Versions\Storage::store)
        //    05/18 - 기존: writeFile(fileId, content) + copyFile(fileId, "files_versions/"+versionId)
        //                 → 원본 1회 + 복사 1회 (2회 I/O)
        //            현재: writeFile(storageKey, content)
        //                 → 최초 버전은 곧 현재 파일이기도 하므로, 우선 버전 스냅샷 자리에 직접 저장.
        //                 → "라이브 파일" 위치는 documents.current_version_id를 통해 간접 참조하는 모델.
        //                   (storage_key 하나로 라이브/스냅샷을 함께 표현)
        //    실 운영에서는 "현재 파일"과 "버전 스냅샷"을 물리적으로 분리하거나
        //    하드링크/카피온라이트로 처리할 수 있음 → 현 의사코드에서는 단일 경로 모델.
        // 파일 먼저 저장, DB 실패 시 보상 삭제
        // [전환 시] 임시 경로 저장 → PENDING → ACTIVE 방식(storage_status)으로 개선 가능
        bool fileWritten = false;
        try {
            fileStorage->writeFile(storageKey, content);
            fileWritten = true;
        } catch (const std::exception& e) {
            throw std::runtime_error(
                std::string("createInitialVersion: 파일 저장 실패 — ") + e.what());
        }

        // 5. VersionInfo 구성 (DB INSERT 직전에 채워둠)
        VersionInfo version;
        version.versionId  = versionId;
        version.fileId     = fileId;
        version.revisionNo = revisionNo;       // 05/18 추가
        version.userId     = userId;
        version.timestamp  = timestamp;
        version.size       = content.size();
        version.mimeType   = content.mimeType;
        version.storageKey = storageKey;       // 05/18 추가
        version.metadata["author"] = userId;

        // 6. documents 테이블에 문서 master 정보 INSERT
        //    05/18 신규: 문서의 평생 식별자(file_id)와 사용자 표시 경로를 documents에 등록.
        //    이후 파일 이동/이름변경은 documents.current_path만 UPDATE하고
        //    file_id는 절대 바뀌지 않음 → 같은 문서 추적이 보장됨.
        // TODO(FileMoveRename):
        //   현재 의사코드에는 파일 이동/이름변경 메서드(예: renameDocument, moveDocument)가
        //   구현되어 있지 않다. 향후 추가 시 반드시 다음 원칙을 지킬 것:
        //     - file_id는 절대 변경하지 않는다 (문서의 평생 식별자)
        //     - documents.current_path만 UPDATE한다
        //     - storage_key("objects/{fileId}/versions/{versionId}")도 변경하지 않는다
        //       (사용자 경로와 내부 저장 경로는 독립)
        //     - file_id 기반 모든 FK(files_versions, version_diffs, systemtag_object_mapping,
        //       approval_rules, file_subscriptions, notifications, retention_policies)는
        //       자연스럽게 그대로 유지됨
        //    original_name은 filePath의 마지막 세그먼트로 두는 것이 통상적이나,
        //    경로 파싱은 의사코드 범위 밖이므로 filePath 전체를 일단 보관.
        //    실 운영에서는 std::filesystem::path / Spring StringUtils 등으로 분리.
        try {
            TransactionGuard tx(*db);

            db->execute(
                "INSERT INTO documents "
                "(file_id, owner_user_id, current_path, original_name, "
                " current_version_id, current_revision_no, created_at, updated_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                {fileId, userId, filePath, filePath,
                 versionId, std::to_string(revisionNo),
                 std::to_string(timestamp), std::to_string(timestamp)}
            );

            // 7. files_versions 테이블에 최초 버전 INSERT
            //    UNIQUE INDEX uq_file_revision(file_id, revision_no)이
            //    같은 파일에서 같은 revision_no 두 번 발급을 DB 차원에서 차단한다.
            std::string metadata = buildVersionMetadataJson(userId);
            db->execute(
                "INSERT INTO files_versions "
                "(version_id, file_id, revision_no, user_id, `timestamp`, "
                " size, mimetype, storage_key, metadata) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                {versionId, fileId, std::to_string(revisionNo), userId,
                 std::to_string(timestamp), std::to_string(version.size),
                 version.mimeType, storageKey, metadata}
            );

            tx.commit();

        } catch (const std::exception& e) {
            // DB 실패 시 이미 저장된 파일을 보상 삭제
            if (fileWritten) {
                try { fileStorage->deleteFile(storageKey); }
                catch (...) {
                    std::cerr << "[createInitialVersion] 보상 파일 삭제 실패: "
                              << storageKey << std::endl;
                }
            }
            throw std::runtime_error(
                std::string("createInitialVersion: DB 저장 실패 — ") + e.what());
        }

        // 8. 활동 로그 기록 (maps to Activity logging)
        //    05/18 - fileId가 이제 UUID이므로 로그 검색 시 documents.current_path 조인 필요.
        //            의사코드 단계에서는 fileId만 기록.
        auditLog->logActivity(userId, fileId, "version_created",
                              "Initial version (revision_no=1)");

        // RD-SRS-9.3: activity 테이블에 버전 생성 이력 기록
        //   변경자(userId), 변경일시(timestamp), versionId를 subjectparams에 포함하여 저장.
        //   기존 auditLog->logActivity()는 별도 audit trail 용도이며,
        //   logDocumentChangeHistory()는 Nextcloud activity 테이블 기록 전용.
        //   versionId를 전달함으로써 files_versions.metadata의 $.reason 갱신도 가능.
        logDocumentChangeHistory(userId, fileId, "version_created",
                                 "revision_no=1",
                                 std::make_optional(versionId));

        // 9. 알림 발송 (commit 이후 후처리 — 실패해도 버전 생성은 완료됨)
        // onDocumentModified()와 동일한 후처리 분리 패턴 적용
        // [전환 시] VersionCreatedEvent 발행 → @EventListener가 비동기 처리
        try {
            notifyStakeholders(fileId, "version_created",
                               "Initial version created by " + userId, {});
        } catch (const std::exception& e) {
            auditLog->logActivity(userId, fileId, "notification_failed",
                std::string("Initial version notification failed: ") + e.what());
        }

        return version;
    }
    // 트랜잭션 한계는 본 메서드 상단 TODO(Transaction/Consistency) 참조.

    // RD-SRS-9.2: 문서 수정 시 버전이 자동으로 업데이트되어야 함
    // Triggered by: NodeWrittenEvent from file modification
    // ID 생성 정책 개정 반영:
    //   [입력] fileId는 이제 UUID라고 가정한다 (createInitialVersion에서 발급된 문서 평생 ID).
    //          호출자는 documents.file_id를 그대로 넘겨야 한다.
    //   [흐름 변경]
    //     - 새 버전의 versionId는 generateUUID()로 발급
    //     - 새 버전의 revisionNo는 documents.current_revision_no + 1
    //     - 스냅샷 저장 경로는 storage_key = "objects/{fileId}/versions/{versionId}"
    //     - documents 테이블의 current_version_id / current_revision_no / updated_at 동시 UPDATE
    // TODO:
    //   실제 구현에서는 documents 행을 SELECT ... FOR UPDATE 등으로 잠가
    //   동시에 같은 문서가 수정될 때 revision_no가 중복되지 않도록 해야 한다.
    //   현 의사코드의 DB는 stub이므로 락 동작은 표현되지 않으며,
    //   대신 files_versions.uq_file_revision(file_id, revision_no) UNIQUE 제약이
    //   마지막 방어선으로 작용한다 (중복 INSERT 시 DB가 거부).
    // TODO(Transaction/Consistency):
    //   현재 C++ 프로토타입에서는 파일 저장과 DB INSERT/UPDATE가 원자적으로 묶여 있지 않다.
    //   파일 저장은 성공했지만 DB 기록이 실패하면 orphan file이 생길 수 있고,
    //   DB 기록은 성공했지만 파일 저장이 실패하면 orphan DB record가 생길 수 있다.
    //   실제 구현에서는 임시 저장 경로, 상태값(PENDING/ACTIVE), 보상 삭제,
    //   DB 트랜잭션 등을 사용해 파일 저장소와 DB의 정합성을 보장해야 한다.

    // * 전체 흐름
    //   documents에서 현재 revision_no 조회
    // → 새 revision_no, 새 versionId, 새 storageKey 산출
    // → 수정 전 파일 내용을 storageKey에 백업
    // → files_versions INSERT, documents UPDATE
    // → diff 계산 → 로그 → 보존 정책 → 알림
    VersionInfo onDocumentModified(const std::string& userId,
                                    const std::string& fileId,
                                    const FileContent& newContent) {
        // 1. 이전 버전 백업 전 이벤트 발생 (maps to CreateVersionEvent)
        //    Nextcloud에서는 이벤트 리스너가 자동으로 처리하지만,
        //    여기서는 명시적 API 호출로 표현

        // 2. documents에서 현재 라이브 상태 조회
        //    05/18 - 새 revision_no 발급을 위한 단조 증가 기준값과
        //            이전 버전의 storage_key(=백업 대상 파일 위치)를 함께 얻는다.
        // TODO(Concurrency):
        //   실제 구현에서는 documents 행을 SELECT ... FOR UPDATE 등으로 잠가
        //   동시에 같은 문서가 수정될 때 같은 revision_no가 생성되지 않도록 해야 한다.
        //   UNIQUE(file_id, revision_no)는 최종 방어선이며,
        //   애플리케이션은 문서 단위 잠금 또는 DB row lock으로 revision_no 증가를 보호해야 한다.
        //   현재 의사코드 한계:
        //     - DatabaseConnection이 stub이라 실제 락 동작을 표현할 수 없음
        //     - 두 동시 요청이 같은 previousRevisionNo를 읽으면 같은 newRevisionNo를 생성
        //     - 두 INSERT 중 하나는 uq_file_revision(file_id, revision_no) 위반으로 거부됨
        //       → 거부된 쪽은 재시도 또는 오류 응답해야 함 (현재 코드는 재시도 미구현)
        //   [Java 전환 시]
        //     - @Transactional + repository.findByIdForUpdate(fileId) (Pessimistic Lock)
        //     - 또는 documents에 @Version 컬럼 추가 후 Optimistic Lock + 재시도 루프
        // 2~8. 전체를 하나의 트랜잭션으로 묶음
        // SELECT ... FOR UPDATE가 트랜잭션 안에서 실행되어야 row lock이 유효함
        // 이전 코드: FOR UPDATE를 tx 밖에서 실행 → autocommit 환경에서 즉시 lock 해제
        //            → 동시 수정 방지 효과 없음
        // 수정: TransactionGuard를 FOR UPDATE 이전으로 이동
        // [전환 시] Pessimistic Lock 또는 Optimistic Lock + 재시도 루프로 대체
        bool fileWritten = false;
        VersionInfo version;
        std::string previousVersionId, previousStorageKey, storageKey, versionId;
        long long previousRevisionNo = 0, newRevisionNo = 0;
        int64_t timestamp = 0;
        FileContent currentContent;  // diff 계산용 이전 버전 내용 (try 블록 밖에서 참조)

        try {
            TransactionGuard tx(*db);

            // 2. documents row lock (FOR UPDATE는 트랜잭션 안에서만 유효)
            auto docRows = db->query(
                "SELECT current_version_id, current_revision_no FROM documents "
                "WHERE file_id = ? FOR UPDATE",
                {fileId}
            );
            if (docRows.empty()) {
                auditLog->logActivity(userId, fileId, "version_update_failed",
                                      "Document not found in documents table");
                return VersionInfo{};  // tx 소멸자가 rollback
            }
            previousVersionId = docRows[0].at("current_version_id");
            previousRevisionNo = std::stoll(docRows[0].at("current_revision_no"));
            newRevisionNo = previousRevisionNo + 1;

            // 이전 버전의 storage_key 조회
            auto prevStorageRows = db->query(
                "SELECT storage_key FROM files_versions WHERE version_id = ? LIMIT 1",
                {previousVersionId}
            );
            // previousStorageKey 누락은 빈 파일이 아닌 DB 정합성 오류
            // → 새 버전 생성을 중단해야 함
            if (prevStorageRows.empty() || prevStorageRows[0].at("storage_key").empty()) {
                auditLog->logActivity(userId, fileId, "version_update_failed",
                    "Previous version storage_key missing: " + previousVersionId);
                return VersionInfo{};  // tx 소멸자가 rollback
            }
            previousStorageKey = prevStorageRows[0].at("storage_key");

            // 3. 수정 전 콘텐츠 읽기
            currentContent = fileStorage->readFile(previousStorageKey);

            // 4. 새 버전 식별자 발급
            versionId  = generateUUID();
            timestamp  = std::chrono::duration_cast<std::chrono::seconds>(
                std::chrono::system_clock::now().time_since_epoch()
            ).count();
            storageKey = "objects/" + fileId + "/versions/" + versionId;

            // 5. 새 콘텐츠를 버전 스냅샷 경로에 저장
            // [전환 시] 임시 경로 저장 → commit 후 rename 방식으로 개선
            fileStorage->writeFile(storageKey, newContent);
            fileWritten = true;

            // 6. VersionInfo 구성
            version.versionId  = versionId;
            version.fileId     = fileId;
            version.revisionNo = newRevisionNo;
            version.userId     = userId;
            version.timestamp  = timestamp;
            version.size       = newContent.size();
            version.mimeType   = newContent.mimeType;
            version.storageKey = storageKey;

            // 7. files_versions INSERT
            std::string metadata = buildVersionMetadataJson(userId);
            db->execute(
                "INSERT INTO files_versions "
                "(version_id, file_id, revision_no, user_id, `timestamp`, "
                " size, mimetype, storage_key, metadata) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                {versionId, fileId, std::to_string(newRevisionNo), userId,
                 std::to_string(timestamp), std::to_string(version.size),
                 version.mimeType, storageKey, metadata}
            );

            // 8. documents 라이브 포인터 갱신
            db->execute(
                "UPDATE documents SET current_version_id = ?, "
                "current_revision_no = ?, updated_at = ? WHERE file_id = ?",
                {versionId, std::to_string(newRevisionNo),
                 std::to_string(timestamp), fileId}
            );

            tx.commit();

        } catch (const std::exception& e) {
            // DB 실패 시 이미 저장된 새 파일 보상 삭제
            if (fileWritten) {
                try { fileStorage->deleteFile(storageKey); }
                catch (...) {
                    std::cerr << "[onDocumentModified] 보상 파일 삭제 실패: "
                              << storageKey << std::endl;
                }
            }
            auditLog->logActivity(userId, fileId, "version_update_failed",
                std::string("onDocumentModified DB 저장 실패: ") + e.what());
            return VersionInfo{};
        }

        // 9. 버전 업데이트 완료 이벤트
        // 이벤트 타입 수정: "version_created" → "version_updated"
        // 이중 알림 수정 (2025-05):
        //   이전: dispatchEvent("version_updated") → 리스너에서 notifyStakeholders()
        //         + 아래 12단계에서 다시 notifyStakeholders() 직접 호출 → 중복 발송
        //   현재: dispatchEvent 제거, 12단계 직접 호출 하나만 유지
        //         dedup key가 같아도 먼저 들어간 짧은 메시지가 저장되고 뒤의
        //         상세 메시지("New revision N by userId")가 버려지는 문제 방지
        // [전환 시] 이벤트 발행 → 리스너 → outbox 저장 구조로 개선 예정
        //   workflowEngine->dispatchEvent("version_updated", {
        //       {"fileId", fileId}, {"versionId", versionId},
        //       {"message", "New revision " + std::to_string(newRevisionNo) + " by " + userId}
        //   });

        // ── commit 이후 후처리
        // 핵심 버전 생성(파일+DB)은 위 트랜잭션에서 이미 완료됨.
        // 이하 후처리는 독립적으로 실패해도 버전 생성 자체는 성공으로 간주.
        // 각각 try/catch로 분리하여 한 단계 실패가 전체를 실패시키지 않도록 함.
        // [전환 시] 각 후처리를 별도 이벤트 리스너(@EventListener)로 분리하면
        //           트랜잭션 commit 이후 비동기 실행으로 더 깔끔하게 처리 가능.

        // 10. diff 캐시 계산 및 저장
        std::string logMsg;
        try {
            if (diffService != nullptr) {
                DiffResult diffResult = diffService->computeDiff(currentContent, newContent);
                logMsg = "Modified: " + diffResult.summary +
                         " (rev " + std::to_string(previousRevisionNo) +
                         " → " + std::to_string(newRevisionNo) + ")";

                // version_diffs INSERT (캐시)
                // 항상 구체적인 versionId 페어로 저장 → 캐시 영구 유효
                db->execute(
                    "INSERT IGNORE INTO version_diffs "
                    "(file_id, from_version_id, to_version_id, diff_method, "
                    " added_lines, deleted_lines, summary, hunks_json, created_at) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    {fileId, previousVersionId, versionId,
                     diffMethodToString(diffResult.method),
                     std::to_string(diffResult.addedLines),
                     std::to_string(diffResult.deletedLines),
                     diffResult.summary, diffResult.unifiedDiff,
                     std::to_string(timestamp)}
                );
            } else {
                logMsg = "Modified: size " + std::to_string(currentContent.size()) +
                         " -> " + std::to_string(newContent.size()) + " bytes";
            }
        } catch (const std::exception& e) {
            // diff 캐시 실패는 버전 생성 성공에 영향 없음 — 로그만 기록
            logMsg = "Modified (diff cache failed): " + std::string(e.what());
            auditLog->logActivity(userId, fileId, "diff_cache_failed", e.what());
        }
        auditLog->logActivity(userId, fileId, "file_modified", logMsg);

        // RD-SRS-9.3: activity 테이블에 버전 수정 이력 기록
        //   변경자(userId), 변경일시(timestamp), 변경 내용(logMsg = diff 요약),
        //   versionId를 함께 저장하여 통합 이력 조회 시 활용 가능.
        //   logMsg 예시: "Modified: 3 lines added, 1 deleted (rev 3 -> 4)"
        //               "Modified (diff cache failed): ..." (diff 실패 시)
        try {
            logDocumentChangeHistory(userId, fileId, "version_updated",
                                     logMsg,
                                     std::make_optional(versionId));
        } catch (const std::exception& e) {
            auditLog->logActivity(userId, fileId, "history_log_failed",
                std::string("logDocumentChangeHistory failed: ") + e.what());
        }

        // 11. 보존 정책 적용 (자동 정리)
        // 실패해도 버전 생성은 이미 완료 — 다음 호출 시 재시도됨
        try {
            policyManager->applyRetentionPolicy(fileId);  // 호환성 유지용 stub
            RetentionPolicy effectivePolicy = evaluatePolicy(fileId);
            applyVersionRetentionPolicy(fileId, effectivePolicy);
        } catch (const std::exception& e) {
            auditLog->logActivity("system", fileId, "retention_cleanup_failed", e.what());
        }

        // 12. 알림 발송
        // 실패해도 버전 생성은 이미 완료 — outbox에 남아 있으면 재시도 가능
        // [전환 시] VersionUpdatedEvent 발행으로 분리
        try {
            notifyStakeholders(fileId, "version_updated",
                               "New revision " + std::to_string(newRevisionNo) +
                               " by " + userId, {});
        } catch (const std::exception& e) {
            auditLog->logActivity("system", fileId, "notification_failed", e.what());
        }

        return version;
    }
    // 자동 트리거 메커니즘은 위 12단계의 notifyStakeholders 호출로 표현.
    //         실 운영에서는 dispatchEvent("version_updated") 발행 후
    //         @EventListener가 notifyStakeholders를 비동기 호출하는 구조로 분리해야 한다.

    // RD-SRS-9.3: 문서 변경 이력에는 수정자, 수정 시각, 변경 내용, 변경 이유가 포함되어야 함
    // 변경 이유(reason)는 Nextcloud 기본 구현에 없어 커스텀 확장 필요
    // versionId 매개변수 추가
    //   문제: 같은 초에 여러 버전이 있으면 잘못된 버전까지 업데이트됨
    //   호출부 호환: 기존 4인자 호출(setDocumentStatus 등)은 기본값 std::nullopt로 동작
    // ID 정책 개정 반영:
    //   - fileId는 UUID (documents.file_id), versionId도 UUID (files_versions.version_id).
    //   - activity.object_id에 fileId(UUID)를 그대로 저장한다.
    //     스키마상 object_id는 VARCHAR(255)이므로 UUID 수용 가능.
    //   - reason은 사용자에게 표시될 수 있으므로 versionId(UUID)보다는
    //     revision_no(사용자 표시용)를 포함하는 것이 가독성이 좋다 (호출자 책임).
    void logDocumentChangeHistory(const std::string& userId,
                                const std::string& fileId,
                                const std::string& action,
                                const std::string& reason = "",
                                const std::optional<std::string>& versionId = std::nullopt) {
        // 1. Activity 앱을 통한 기록 (maps to Activity\Data::send)
        ActivityEntry activity;
        activity.userId = userId;
        activity.action = action;
        // 9.1과 동일하게 초 단위로 통일
        activity.timestamp = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();
        activity.subject = "file_" + action;
        activity.objectType = "files";
        activity.objectId = fileId;
        // message 필드 설정
        activity.message = action + (reason.empty() ? "" : " - Reason: " + reason);

        // reason 필드는 Nextcloud 기본 스키마에 없음
        // 커스텀 구현: metadata JSON 필드 또는 별도 테이블 필요
        if (!reason.empty()) {
            activity.reason = reason;
            // 특정 버전이 명확할 때만 version metadata 갱신
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
        // MariaDB 호환: timestamp, user는 예약어이므로 백틱 필요
        // RD-SRS-9.3: subjectparams에 구조화된 변경 정보 저장
        //   기존: "{}" (빈 JSON) → 이력 조회 시 상세 내용 없음
        //   변경: action, fileId, versionId(optional), reason(optional) 포함
        //   parseJson()으로 역파싱하거나 JSON 경로 조회로 reason, versionId 추출 가능
        std::string subjectParams = std::string("{")
            + "\"action\":\"" + escapeJsonString(action) + "\""
            + ",\"fileId\":\"" + escapeJsonString(fileId) + "\"";
        if (versionId.has_value() && !versionId->empty()) {
            subjectParams += ",\"versionId\":\"" + escapeJsonString(*versionId) + "\"";
        }
        if (!reason.empty()) {
            subjectParams += ",\"reason\":\"" + escapeJsonString(reason) + "\"";
        }
        subjectParams += "}";

        db->execute("INSERT INTO activity (`timestamp`, `user`, affecteduser, app, subject, "
                    "subjectparams, file, object_type, object_id) "
                    "VALUES (?, ?, ?, 'files', ?, ?, ?, ?, ?)",
                    {std::to_string(activity.timestamp), activity.userId, activity.userId, activity.subject,
                    subjectParams, activity.objectId, activity.objectType, activity.objectId});

        // 3. Admin Audit 로그 (maps to Admin_Audit\Files)
        if (isAdminAuditEnabled()) {
            std::string auditLogMsg = "[" + std::to_string(activity.timestamp) + "] "
                                + "User: " + userId + ", Action: " + action
                                + ", File: " + fileId;
            if (!reason.empty()) {
                auditLogMsg += ", Reason: " + reason;
            }
            // versionId가 있으면 감사 로그에 포함
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
    // 헬퍼: DiffMethod enum → 문자열 변환
    //   version_diffs 테이블 저장용. Java 전환 시 enum.name() 대체
    static std::string diffMethodToString(DiffMethod method) {
        switch (method) {
            case DiffMethod::TEXT_DIRECT:    return "myers";
            case DiffMethod::TEXT_EXTRACTED: return "myers_extracted";
            case DiffMethod::HASH_ONLY:      return "sha256";
        }
        return "unknown";
    }

    // 헬퍼 추가: 문자열 → DiffMethod enum 역변환
    //   prepareVersionComparison의 캐시 hit 경로에서 사용
    //   알 수 없는 값(레거시, 손상된 데이터)은 안전 기본값 TEXT_DIRECT 반환
    static DiffMethod stringToDiffMethod(const std::string& s) {
        if (s == "myers")           return DiffMethod::TEXT_DIRECT;
        if (s == "myers_extracted") return DiffMethod::TEXT_EXTRACTED;
        if (s == "sha256")          return DiffMethod::HASH_ONLY;
        return DiffMethod::TEXT_DIRECT;  // 안전 기본값
    }

    // 저장된 diff 캐시 직접 조회 (UI에서 호출)
    //   prepareVersionComparison 없이 캐시만 빠르게 조회하고 싶을 때 사용
    //   반환: 캐시 hit 시 hunks_json + 메타, miss 시 빈 결과
    // ID 정책 개정 반영:
    //   - fileId / fromVersionId / toVersionId는 모두 UUID 문자열로 가정.
    //   - 호출자가 "current"를 넘기는 경우, prepareVersionComparison이
    //     사전에 documents.current_version_id로 해석해 구체 versionId로 넘기는 것이 권장.
    //     본 함수 자체는 단순 SELECT이므로 변경 없음.
    //   - 호출자가 versionId 문자열에서 timestamp/저장 경로를 추론해서는 안 된다.
    //     (UUID는 의미 없는 식별자이며, 메타 정보가 필요하면 files_versions를 조회해야 함)
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

    // 기존: 서버는 콘텐츠만 제공, diff는 클라이언트 담당
    //         변경: DiffService를 통해 서버 측에서 diff 계산 후 결과를 포함하여 반환
    // 캐시 우선 조회 (Q8=A 결정)
    //   동작: version_diffs 캐시 hit 시 즉시 반환, miss 시 계산 후 INSERT
    // ID 정책 개정 반영:
    //   - versionId는 UUID. versionId 문자열에서 경로를 조립하지 않음.
    //   - 실제 파일은 항상 files_versions.storage_key로 조회 → fileStorage->readFile(storage_key)
    //   - "current" 특수값 의존을 줄임:
    //       기존: versionId == "current"이면 fileStorage->readFile(fileId)
    //       변경: documents.current_version_id를 조회하여 실제 versionId로 해석한 뒤 storage_key 조회
    //       (호환을 위해 "current" 입력은 받되, 내부적으로 즉시 구체적 versionId로 변환)
    DiffInfo prepareVersionComparison(const std::string& userId,
                                    const std::string& fileId,
                                    const std::string& versionId1,
                                    const std::string& versionId2) {
        DiffInfo diff;
        diff.versionId1 = versionId1;
        diff.versionId2 = versionId2;

        // "current" → 실제 UUID 해석 헬퍼 (람다)
        //   호환성: 기존 호출자가 "current"를 넘겨도 동작하되, 내부에서는 구체 versionId로 일원화.
        //   이후 storage_key 조회/캐시 키 모두 구체 ID로 통일됨.
        auto resolveVersionId = [&](const std::string& vid) -> std::string {
            if (vid != "current") return vid;
            auto rows = db->query(
                "SELECT current_version_id FROM documents WHERE file_id = ? LIMIT 1",
                {fileId}
            );
            return rows.empty() ? vid : rows[0].at("current_version_id");
        };
        std::string resolvedVid1 = resolveVersionId(versionId1);
        std::string resolvedVid2 = resolveVersionId(versionId2);

        // storage_key 조회 헬퍼
        //   05/18 - 실제 파일 위치는 항상 files_versions.storage_key에서 얻는다.
        //           versionId 문자열로 경로를 추론하지 않는다.
        auto getStorageKey = [&](const std::string& vid) -> std::string {
            auto rows = db->query(
                "SELECT storage_key FROM files_versions WHERE version_id = ? LIMIT 1",
                {vid}
            );
            return rows.empty() ? "" : rows[0].at("storage_key");
        };

        // 0. 캐시 조회 (04/30 추가)
        //    05/18 - 캐시 키는 구체 versionId 페어로 사용 (resolveVersionId 적용 후)
        auto cached = getVersionDiff(fileId, resolvedVid1, resolvedVid2);
        if (!cached.empty()) {
            // 캐시 hit
            diff.diffResult.summary       = cached.at("summary");
            diff.diffResult.unifiedDiff   = cached.at("hunks_json");
            diff.diffResult.addedLines    = std::stoi(cached.at("added_lines"));
            diff.diffResult.deletedLines  = std::stoi(cached.at("deleted_lines"));
            // diff_method 복원
            diff.diffResult.method        = stringToDiffMethod(cached.at("diff_method"));

            // 콘텐츠 로드 (캐시는 diff 결과만, 콘텐츠는 별도)
            std::string sk1 = getStorageKey(resolvedVid1);
            std::string sk2 = getStorageKey(resolvedVid2);
            // storage_key 누락은 "빈 파일"이 아니라 "조회 불가" 상태임
            // 빈 FileContent로 처리하면 diff에서 "전체 내용 추가/삭제"로 잘못 표시됨
            if (sk1.empty() || sk2.empty()) {
                auditLog->logActivity(userId, fileId, "diff_failed",
                    "storage_key 누락: vid1=" + resolvedVid1 + " vid2=" + resolvedVid2);
                return DiffInfo{};
            }
            try {
                diff.content1 = fileStorage->readFile(sk1);
                diff.content2 = fileStorage->readFile(sk2);
            } catch (const std::exception& e) {
                auditLog->logActivity(userId, fileId, "diff_failed",
                    std::string("파일 로드 실패: ") + e.what());
                return DiffInfo{};
            }

            auditLog->logActivity(userId, fileId, "version_compared",
                resolvedVid1 + " vs " + resolvedVid2 + " (cache hit)");
            return diff;
        }

        // 1~2. 두 버전 콘텐츠 조회
        // storage_key 누락 또는 파일 읽기 실패는 빈 파일이 아닌 명시적 실패로 처리
        std::string sk1 = getStorageKey(resolvedVid1);
        std::string sk2 = getStorageKey(resolvedVid2);
        if (sk1.empty() || sk2.empty()) {
            auditLog->logActivity(userId, fileId, "diff_failed",
                "storage_key 누락: vid1=" + resolvedVid1 + " vid2=" + resolvedVid2);
            return DiffInfo{};
        }
        try {
            diff.content1 = fileStorage->readFile(sk1);
            diff.content2 = fileStorage->readFile(sk2);
        } catch (const std::exception& e) {
            auditLog->logActivity(userId, fileId, "diff_failed",
                std::string("파일 로드 실패: ") + e.what());
            return DiffInfo{};
        }

        // 3. 서버 측 diff 계산 (03/13 추가)
        if (diffService != nullptr) {
            diff.diffResult = diffService->computeDiff(diff.content1, diff.content2);

            // 계산 결과를 캐시에 저장
            //   05/18 - 캐시 키는 구체 versionId 페어. "current"를 저장하지 않으므로
            //           시간이 지나도 캐시 항목의 의미가 변하지 않음 (영구 유효).
            auto now = std::chrono::duration_cast<std::chrono::seconds>(
                std::chrono::system_clock::now().time_since_epoch()
            ).count();
            db->execute(
                "INSERT IGNORE INTO version_diffs "
                "(file_id, from_version_id, to_version_id, diff_method, "
                " added_lines, deleted_lines, summary, hunks_json, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                {fileId, resolvedVid1, resolvedVid2,
                 diffMethodToString(diff.diffResult.method),
                 std::to_string(diff.diffResult.addedLines),
                 std::to_string(diff.diffResult.deletedLines),
                 diff.diffResult.summary, diff.diffResult.unifiedDiff,
                 std::to_string(now)}
            );
        }
        // diffService가 nullptr인 경우: 콘텐츠만 반환 (기존 동작 유지, 하위 호환)

        // Dead Code 제거: versionInfo1, versionInfo2 DB 조회 제거 (기존)

        // 4. 활동 로그
        std::string logMsg = resolvedVid1 + " vs " + resolvedVid2;
        if (diffService != nullptr && !diff.diffResult.summary.empty()) {
            logMsg += " (" + diff.diffResult.summary + ", computed)";
        }
        auditLog->logActivity(userId, fileId, "version_compared", logMsg);

        return diff;
    }

    // RD-SRS-9.5: 특정 시점의 문서 버전을 확인하고 조회할 수 있어야 함

    // 페이지네이션 메타데이터용 전체 카운트
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

    // ============================================================
    // RD-SRS-9.3: 통합 변경 이력 조회 (getDocumentHistory)
    // ------------------------------------------------------------
    // 목적: 변경자·변경일시·변경 내용·변경 사유를 하나의 타임라인으로 반환.
    //       기존 코드에서는 files_versions, activity, approval_activity가
    //       분산 저장되어 있어 호출자가 직접 병합해야 했음 (RD-SRS-9.3 미충족).
    //       이 메서드가 세 소스를 통합하여 단일 이력 목록으로 제공한다.
    // 소스별 역할:
    //   files_versions  → 버전 생성/수정 이력 (변경자, 시각, revision_no, diff 요약)
    //   activity        → 상태 변경 이력 (status_changed, version_created 등)
    //                     Fix 3에서 subjectparams에 구조화 JSON 저장으로 개선됨
    //   approval_activity → 승인/거절/취소 이력 (approver, action, comment)
    // 반환 순서: timestamp DESC (최신 이력이 먼저)
    // limit/offset: 페이지네이션 지원
    // [Java 전환 시] DocumentHistoryService @Service 빈으로 분리.
    //               Stream.concat() + Comparator로 병합 후 Page<HistoryEntry> 반환.
    //               @Cacheable로 최근 이력 캐시 적용 가능.
    // ============================================================
    std::vector<HistoryEntry> getDocumentHistory(const std::string& userId,
                                                  const std::string& fileId,
                                                  int limit = 50,
                                                  int offset = 0) {
        if (fileId.empty()) return {};

        constexpr int kMaxLimit = 100;
        limit  = std::max(1, std::min(limit, kMaxLimit));
        offset = std::max(0, offset);

        std::vector<HistoryEntry> entries;

        // ── 소스 1: files_versions — 버전 생성/수정 이력
        // version_diffs와 LEFT JOIN하여 diff 요약(summary)을 함께 조회.
        // LEFT JOIN 이유: diff 계산이 실패했거나 최초 버전인 경우 version_diffs가 없을 수 있음.
        auto versionRows = db->query(
            "SELECT fv.version_id, fv.user_id, fv.`timestamp`, fv.revision_no, "
            "       fv.metadata, "
            "       COALESCE(vd.summary, '') AS diff_summary "
            "FROM files_versions fv "
            "LEFT JOIN version_diffs vd "
            "  ON vd.file_id = fv.file_id AND vd.to_version_id = fv.version_id "
            "WHERE fv.file_id = ? "
            "ORDER BY fv.`timestamp` DESC",
            {fileId}
        );

        for (const auto& row : versionRows) {
            HistoryEntry e;
            e.source     = "versions";
            e.userId     = row.at("user_id");
            e.timestamp  = std::stoll(row.at("timestamp"));
            e.versionId  = row.at("version_id");
            e.revisionNo = row.at("revision_no");

            // revision_no=1 이면 최초 생성, 그 외는 수정
            e.action = (e.revisionNo == "1") ? "version_created" : "version_updated";

            // diff 요약이 있으면 변경 내용으로, 없으면 revision 정보로 표시
            std::string diffSummary = row.at("diff_summary");
            e.summary = diffSummary.empty()
                        ? "revision_no=" + e.revisionNo
                        : diffSummary;

            // metadata JSON에서 reason 추출 (Fix 3에서 저장된 구조화 JSON 활용)
            std::string metadata = row.count("metadata") ? row.at("metadata") : "";
            if (!metadata.empty()) {
                auto metaMap = parseJson(metadata);
                if (metaMap.count("reason") && !metaMap.at("reason").empty()) {
                    e.reason = metaMap.at("reason");
                }
            }
            entries.push_back(std::move(e));
        }

        // ── 소스 2: activity — 상태 변경 이력
        // logDocumentChangeHistory()가 남긴 기록 중 상태 변경 계열만 조회.
        // version_created / version_updated는 소스 1(files_versions)에서 이미 처리하므로
        // activity에서는 제외하여 중복 이력 방지.
        // 포함 대상: status_changed, status_restored, approved_reverted 등
        auto activityRows = db->query(
            "SELECT `user`, `timestamp`, subject, subjectparams "
            "FROM activity "
            "WHERE object_id = ? AND object_type = 'files' "
            "  AND subject NOT IN ('file_version_created', 'file_version_updated') "
            "ORDER BY `timestamp` DESC",
            {fileId}
        );

        for (const auto& row : activityRows) {
            HistoryEntry e;
            e.source    = "activity";
            e.userId    = row.at("user");
            e.timestamp = std::stoll(row.at("timestamp"));

            // subject 예시: "file_status_changed", "file_version_created"
            std::string subject = row.at("subject");
            // "file_" 접두어 제거 → 순수 action 이름
            e.action = (subject.rfind("file_", 0) == 0) ? subject.substr(5) : subject;

            // subjectparams JSON 파싱 (Fix 3에서 구조화됨)
            std::string params = row.count("subjectparams") ? row.at("subjectparams") : "";
            if (!params.empty() && params != "{}") {
                auto paramsMap = parseJson(params);
                if (paramsMap.count("versionId")) e.versionId = paramsMap.at("versionId");
                if (paramsMap.count("reason"))    e.reason    = paramsMap.at("reason");
            }

            e.summary = e.action;  // summary는 action 명칭으로 기본 설정
            entries.push_back(std::move(e));
        }

        // ── 소스 3: approval_activity — 승인/거절/취소 이력
        auto approvalRows = db->query(
            "SELECT aa.user_id, aa.created_at, aa.action, aa.comment, "
            "       ar.file_id "
            "FROM approval_activity aa "
            "JOIN approval_rules ar ON aa.rule_id = ar.id "
            "WHERE ar.file_id = ? "
            "ORDER BY aa.created_at DESC",
            {fileId}
        );

        for (const auto& row : approvalRows) {
            HistoryEntry e;
            e.source    = "approval";
            e.userId    = row.at("user_id");
            e.timestamp = std::stoll(row.at("created_at"));
            e.action    = "approval_" + row.at("action");  // "approval_approved" 등
            e.reason    = row.count("comment") ? row.at("comment") : "";
            e.summary   = row.at("action");  // "approved", "rejected", "cancelled"
            entries.push_back(std::move(e));
        }

        // ── 병합: timestamp DESC 정렬
        std::sort(entries.begin(), entries.end(),
            [](const HistoryEntry& a, const HistoryEntry& b) {
                return a.timestamp > b.timestamp;
            });

        // ── 페이지네이션 적용
        if (offset >= static_cast<int>(entries.size())) return {};

        int end = std::min(static_cast<int>(entries.size()), offset + limit);
        std::vector<HistoryEntry> page(entries.begin() + offset,
                                       entries.begin() + end);

        auditLog->logActivity(userId, fileId, "history_listed",
            "offset=" + std::to_string(offset) + ", limit=" + std::to_string(limit)
            + ", total=" + std::to_string(entries.size()));

        return page;
    }

    // 통합 변경 이력 전체 건수 (페이지네이션 메타데이터용)
    int64_t countDocumentHistory(const std::string& fileId) {
        if (fileId.empty()) return 0;

        auto vRows = db->query(
            "SELECT COUNT(*) AS cnt FROM files_versions WHERE file_id = ?", {fileId});
        auto aRows = db->query(
            "SELECT COUNT(*) AS cnt FROM activity "
            "WHERE object_id = ? AND object_type = 'files' "
            "  AND subject NOT IN ('file_version_created', 'file_version_updated')",
            {fileId});
        auto apRows = db->query(
            "SELECT COUNT(*) AS cnt FROM approval_activity aa "
            "JOIN approval_rules ar ON aa.rule_id = ar.id WHERE ar.file_id = ?",
            {fileId});

        int64_t total = 0;
        if (!vRows.empty())  total += std::stoll(vRows[0].at("cnt"));
        if (!aRows.empty())  total += std::stoll(aRows[0].at("cnt"));
        if (!apRows.empty()) total += std::stoll(apRows[0].at("cnt"));
        return total;
    }

    std::vector<VersionInfo> getVersionsAtTime(const std::string& userId,
                                                const std::string& fileId,
                                                int64_t targetTimestamp,
                                                int limit = 50,
                                                int offset = 0) {
        // ID 정책 개정 반영:
        //   - fileId는 UUID (documents.file_id). 호출자는 documents 조회로 미리 얻어야 함.
        //   - SELECT에 revision_no, storage_key 컬럼 추가 → VersionInfo 신규 필드 채움.
        //   - versionId 문자열로 저장 경로를 추론하지 않는다 (storage_key 컬럼 사용).
        std::vector<VersionInfo> versions;

        // limit 범위 방어
        // (Q10=A): 기본 limit 10 → 50으로 상향 (UI 페이지 사이즈)
        constexpr int kMinLimit = 1;
        constexpr int kMaxLimit = 100;
        if (limit < kMinLimit) limit = kMinLimit;
        else if (limit > kMaxLimit) limit = kMaxLimit;
        // offset 음수 방어
        if (offset < 0) offset = 0;

        // 1. 모든 버전 목록 조회 (maps to Storage::getVersions)
        // OFFSET 추가 (페이지네이션 지원)
        // SELECT 절에 revision_no, storage_key 추가
        auto results = db->query(
            "SELECT version_id, file_id, revision_no, user_id, `timestamp`, "
            "       size, mimetype, storage_key, metadata "
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
            version.fileId     = row.at("file_id");
            version.versionId  = row.at("version_id");      // DB에서 직접 조회
            version.revisionNo = row.count("revision_no")   // 05/18 추가
                                 ? std::stoll(row.at("revision_no")) : 0;
            version.timestamp  = std::stoll(row.at("timestamp"));
            version.size       = std::stoull(row.at("size"));
            version.mimeType   = row.at("mimetype");
            version.storageKey = row.count("storage_key")   // 05/18 추가
                                 ? row.at("storage_key") : "";

            // user_id 컬럼 우선, metadata fallback
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
                "SELECT version_id, file_id, revision_no, user_id, `timestamp`, "
                "       size, mimetype, storage_key, metadata "
                "FROM files_versions "
                "WHERE file_id = ? AND `timestamp` > ? "
                "ORDER BY `timestamp` ASC LIMIT 1",
                {fileId, std::to_string(targetTimestamp)}
            );

            if (!futureResults.empty()) {
                const auto& row = futureResults[0];
                VersionInfo version;
                version.fileId     = row.at("file_id");
                version.versionId  = row.at("version_id");
                version.revisionNo = row.count("revision_no")
                                     ? std::stoll(row.at("revision_no")) : 0;
                version.timestamp  = std::stoll(row.at("timestamp"));
                version.size       = std::stoull(row.at("size"));
                version.mimeType   = row.at("mimetype");
                version.storageKey = row.count("storage_key")
                                     ? row.at("storage_key") : "";
                // user_id 컬럼 우선, metadata fallback
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
    //       lib/public/SystemTag/ISystemTagManager.php (createTag/updateTag)
    //       lib/public/SystemTag/ISystemTagObjectMapper.php (assignTags)
    // Called via: OCS API POST /ocs/v2.php/apps/systemtags
    // ID 정책 개정 반영:
    //   - fileId는 UUID (documents.file_id, CHAR(36))로 가정.
    //   - 본 메서드는 systemtag_object_mapping.object_id에 fileId를 그대로 사용한다.
    //     스키마상 object_id는 VARCHAR(255)이므로 UUID(36자) 수용 가능.
    //   - 상태 변경은 documents의 라이브 콘텐츠를 건드리지 않으므로
    //     current_version_id / current_revision_no는 변경되지 않는다.
    // ============================================================
    // safeNotify — commit 이후 알림 안전 발송 헬퍼
    // ------------------------------------------------------------
    // "DB 변경은 성공했는데 알림 실패로 API 전체가 실패처럼 보이는" 문제를
    // 일관되게 방어하기 위한 내부 헬퍼.
    // 모든 commit 이후 알림은 이 메서드를 통해 발송한다.
    // 실패 시 notification_failed audit log만 남기고 호출부로 예외를 전파하지 않음.
    void safeNotify(const std::string& actorUserId,
                    const std::string& fileId,
                    const std::string& eventType,
                    const std::string& message,
                    const std::vector<NotificationTarget>& targets,
                    const std::string& failContext = "") {
        try {
            notifyStakeholders(fileId, eventType, message, targets);
        } catch (const std::exception& e) {
            std::string ctx = failContext.empty() ? eventType : failContext;
            auditLog->logActivity(
                actorUserId.empty() ? "system" : actorUserId,
                fileId, "notification_failed",
                ctx + ": " + e.what()
            );
        }
    }

    // evaluateRules + safeNotify를 묶어서 호출하는 헬퍼
    void safeEvalAndNotify(const std::string& actorUserId,
                           const std::string& fileId,
                           const std::string& tagName,
                           const std::string& notifyEvent,
                           const std::string& notifyMessage) {
        try {
            workflowEngine->evaluateRules("tag_assigned", {
                {"fileId", fileId}, {"tagName", tagName}, {"userId", actorUserId}
            });
        } catch (const std::exception& e) {
            auditLog->logActivity(actorUserId, fileId, "workflow_eval_failed",
                std::string("evaluateRules failed: ") + e.what());
        }
        safeNotify(actorUserId, fileId, notifyEvent, notifyMessage, {});
    }

    // ============================================================
    // setDocumentStatusInternal
    // ------------------------------------------------------------
    // DB 태그 변경만 수행 (알림/워크플로우 트리거 없음).
    // 트랜잭션 안에서 setDocumentStatus를 호출하면 트랜잭션 내부에서
    // notifyStakeholders → flushOutboxImmediate가 실행되는 문제가 있음.
    // (외부 알림이 나간 뒤 트랜잭션이 rollback되면 DB는 되돌아갔는데 알림은 나간 상태)
    // 사용처:
    //   - processApprovalDecision의 트랜잭션 안 (상태 전이 + rule close를 원자적으로)
    //   - processApprovalWorkflow의 규칙 생성 트랜잭션 안
    // 알림/워크플로우는 commit 이후 호출부가 직접 처리해야 함.
    // [전환 시] @Transactional 내부에서는 이벤트 발행만 하고,
    //           실제 알림은 트랜잭션 commit 후 이벤트 리스너가 처리.
    // ============================================================
    bool setDocumentStatusInternal(const std::string& userId,
                                   const std::string& fileId,
                                   DocumentStatus status,
                                   const std::string& comment = "") {
        std::string tagName;
        switch (status) {
            case DocumentStatus::DRAFT:         tagName = TAG_DRAFT; break;
            case DocumentStatus::UNDER_REVIEW:  tagName = TAG_UNDER_REVIEW; break;
            case DocumentStatus::APPROVED:      tagName = TAG_APPROVED; break;
            case DocumentStatus::REJECTED:      tagName = TAG_REJECTED; break;
            case DocumentStatus::DEPRECATED:    tagName = TAG_DEPRECATED; break;
            default:
                throw std::invalid_argument("Unknown DocumentStatus: " +
                    std::to_string(static_cast<int>(status)));
        }

        std::string currentTag = getCurrentStatusTag(fileId);
        if (!isValidTransition(currentTag, tagName)) {
            auditLog->logActivity(userId, fileId, "status_change_denied",
                "Invalid transition: " + (currentTag.empty() ? "(none)" : currentTag)
                + " -> " + tagName);
            return false;
        }

        auto tagResult = db->query("SELECT id FROM systemtag WHERE name = ?", {tagName});
        std::string tagId;
        if (tagResult.empty()) {
            tagId = generateUUID();
            db->execute("INSERT INTO systemtag (id, name, visibility, editable) "
                        "VALUES (?, ?, 1, 1)", {tagId, tagName});
        } else {
            tagId = tagResult[0]["id"];
        }

        std::vector<std::string> statusTags = {
            TAG_DRAFT, TAG_UNDER_REVIEW, TAG_APPROVED, TAG_REJECTED, TAG_DEPRECATED
        };
        for (const auto& oldTag : statusTags) {
            if (oldTag != tagName) {
                db->execute("DELETE FROM systemtag_object_mapping "
                            "WHERE objectid = ? AND objecttype = 'files' "
                            "AND systemtagid IN (SELECT id FROM systemtag WHERE name = ?)",
                            {fileId, oldTag});
            }
        }

        db->execute("REPLACE INTO systemtag_object_mapping "
                    "(objectid, objecttype, systemtagid) VALUES (?, 'files', ?)",
                    {fileId, tagId});

        // 이력 기록은 내부에서 수행 (DB 기록이므로 트랜잭션과 함께 commit/rollback됨)
        logDocumentChangeHistory(userId, fileId, "status_changed",
                                 "Changed to " + tagName + ": " + comment);
        return true;
    }

    // ============================================================
    // setDocumentStatus (공개 인터페이스 — 알림/워크플로우 포함)
    // ============================================================
    bool setDocumentStatus(const std::string& userId,
                        const std::string& fileId,
                        DocumentStatus status,
                        const std::string& comment = "") {
        // DB 변경 (트랜잭션과 무관하게 단독 호출 시 auto-commit으로 동작)
        if (!setDocumentStatusInternal(userId, fileId, status, comment)) {
            return false;
        }

        std::string tagName;
        switch (status) {
            case DocumentStatus::DRAFT:         tagName = TAG_DRAFT; break;
            case DocumentStatus::UNDER_REVIEW:  tagName = TAG_UNDER_REVIEW; break;
            case DocumentStatus::APPROVED:      tagName = TAG_APPROVED; break;
            case DocumentStatus::REJECTED:      tagName = TAG_REJECTED; break;
            case DocumentStatus::DEPRECATED:    tagName = TAG_DEPRECATED; break;
            default: return true;
        }

        // commit 이후 워크플로우/알림 처리 (트랜잭션 밖 — 실패해도 상태 변경은 완료됨)
        safeEvalAndNotify(userId, fileId, tagName, "status_changed",
            "Document status changed to " + tagName +
            (comment.empty() ? "" : ": " + comment));

        return true;
    }

    // RD-SRS-9.7: 문서 승인 워크플로우 및 승인 프로세스 관리
    // Approval 앱은 별도 저장소이므로 기본 기능을 모방
    // 합의 모드 + 임계값 매개변수 추가 (호환성 위해 기본값 제공)
    //   기존 호출(consensusMode/requiredApprovals 미지정): THRESHOLD 모드, 1명 승인
    //                                                   = 기존 "첫 승인자가 결정" 동작과 동일
    //   새 호출 시 SEQUENTIAL 모드면 approvers 순서가 sequence_order로 사용됨
    // ID 정책 개정 반영:
    //   - fileId는 UUID (documents.file_id)로 가정.
    //   - approval_rules.target_file_id 컬럼은 VARCHAR이므로 UUID 그대로 저장 가능.
    //   - 승인 워크플로우는 문서의 라이브 콘텐츠를 건드리지 않으므로
    //     documents 테이블은 본 메서드에서 변경되지 않는다 (상태 태그만 변경됨).
    //   - 의미상 "승인은 특정 revision_no를 대상으로 한다"는 모델이 합리적이며,
    //     이후 확장 시 approval_rules에 target_version_id(또는 target_revision_no)를
    //     추가하면 "어느 시점의 콘텐츠를 승인했는가"가 명확해진다 (현 의사코드 범위 외).
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
                // 선행 검증 1: 빈 승인자 목록 방어
                // 승인자가 없으면 누구도 APPROVE/REJECT할 수 없어 문서가 UNDER_REVIEW에 영구 체류
                if (approvers.empty()) {
                    auditLog->logActivity(userId, fileId, "approval_failed",
                                        "No approvers specified for approval request");
                    break;  // success = false 유지
                }

                // 임계값 정규화 (모드별 의미 통일)
                // 승인자 목록을 먼저 중복 제거 — required_approvals 계산도 여기서 통일
                // 버그 수정: 원본 approvers.size()로 required를 계산하면
                //   입력: [A, A, B] → 실제 저장: [A, B] 2명인데 required=3이 되어
                //   영원히 승인 완료가 될 수 없는 규칙이 생성됨
                std::vector<std::string> uniqueApprovers;
                {
                    std::unordered_set<std::string> seen;
                    for (const auto& ap : approvers) {
                        if (seen.insert(ap).second) uniqueApprovers.push_back(ap);
                    }
                }

                int effectiveRequired = requiredApprovals;
                if (consensusMode == ApprovalConsensusMode::UNANIMOUS) {
                    effectiveRequired = static_cast<int>(uniqueApprovers.size());
                } else if (consensusMode == ApprovalConsensusMode::SEQUENTIAL) {
                    effectiveRequired = static_cast<int>(uniqueApprovers.size());
                } else {
                    if (effectiveRequired < 1) effectiveRequired = 1;
                    if (effectiveRequired > static_cast<int>(uniqueApprovers.size())) {
                        effectiveRequired = static_cast<int>(uniqueApprovers.size());
                    }
                }

                // 선행 검증 2: 태그 기반 중복 승인 요청 방어 (Nextcloud 방식)
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

                // 현재 구조: 상태 변경(setDocumentStatusInternal)과
                //   approval_rules/requesters/approvers INSERT가 하나의 TransactionGuard 안에서 처리됨.
                //   중간 실패 시 rollback으로 상태와 규칙이 함께 취소됨.
                // 아직 남은 한계 (Java 전환 시 개선):
                //   pending 여부 확인(pendingCheck)이 트랜잭션 밖에서 수행되므로
                //   동시에 두 요청이 들어올 경우 race condition이 완전히 제거되지 않음.
                //   Java 전환 시: documents SELECT ... FOR UPDATE로 row lock 후
                //   트랜잭션 안에서 pending 재확인 + 상태 변경 + 규칙 생성을 원자적으로 처리.
                //   Java 전환 시 권장 구조:
                //     @Transactional
                //     → setDocumentStatusInternal(UNDER_REVIEW)  // 알림 없음
                //     → approval_rules INSERT
                //     → requesters INSERT
                //     → approvers INSERT
                //     → commit
                //     → commit 이후 approval_requested 알림 발송
                //   또한 pending 여부 확인도 트랜잭션 안에서 row lock으로 처리해야
                //   동시 승인 요청 경합을 완전히 방지할 수 있음.

                // 1~4. 상태 변경 + 승인 규칙 생성을 하나의 트랜잭션으로 묶음
                // 버그 수정: 이전 코드는 setDocumentStatus(UNDER_REVIEW)를 트랜잭션 밖에서
                //   호출하여 알림이 먼저 나간 뒤 규칙 생성이 실패하면 DRAFT 복원 알림까지
                //   이중으로 발송되는 문제가 있었음.
                //   → setDocumentStatusInternal(알림 없음)을 트랜잭션 안에서 사용하고
                //     알림은 commit 이후 한 번만 발송하도록 수정.
                // [전환 시] @Transactional 하나로 전체를 감싸고,
                //   commit 이후 approval_requested 이벤트를 발행하는 구조로 대체.
                std::string ruleId = generateUUID();
                try {
                    TransactionGuard tx(*db);

                    // 트랜잭션 안에서 pending rule 이중 체크 (race condition 완화)
                    // 트랜잭션 밖 pendingCheck와 이 시점 사이에 다른 요청이 들어왔을 경우 방어
                    auto openRuleCheck = db->query(
                        "SELECT id FROM approval_rules "
                        "WHERE file_id = ? AND status = 'OPEN' LIMIT 1",
                        {fileId}
                    );
                    if (!openRuleCheck.empty()) {
                        auditLog->logActivity(userId, fileId, "approval_failed",
                            "Open approval rule already exists (race condition detected)");
                        break;  // tx 소멸자가 rollback
                    }

                    // 상태 전이 (Internal: 알림/워크플로우 트리거 없음)
                    if (!setDocumentStatusInternal(userId, fileId,
                                                   DocumentStatus::UNDER_REVIEW,
                                                   "Approval requested: " + comment)) {
                        auditLog->logActivity(userId, fileId, "approval_failed",
                                              "Failed to transition to UNDER_REVIEW");
                        break;  // tx 소멸자가 rollback
                    }

                    db->execute(
                        "INSERT INTO approval_rules "
                        "(id, file_id, tag_pending, tag_approved, tag_rejected, status, "
                        " consensus_mode, required_approvals, received_approvals, received_rejections) "
                        "VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?, 0, 0)",
                        std::vector<std::string>{ruleId, fileId, TAG_UNDER_REVIEW,
                         TAG_APPROVED, TAG_REJECTED,
                         consensusModeToString(consensusMode),
                         std::to_string(effectiveRequired)}
                    );

                    db->execute("INSERT INTO approval_rule_requesters (rule_id, entity_type, entity_id) "
                                "VALUES (?, 'user', ?)",
                                std::vector<std::string>{ruleId, userId});

                    int seqOrder = 1;
                    for (const auto& approver : uniqueApprovers) {
                        int orderValue = (consensusMode == ApprovalConsensusMode::SEQUENTIAL)
                                         ? seqOrder : 0;
                        db->execute(
                            "INSERT INTO approval_rule_approvers "
                            "(rule_id, entity_type, entity_id, sequence_order) "
                            "VALUES (?, 'user', ?, ?)",
                            std::vector<std::string>{ruleId, approver, std::to_string(orderValue)}
                        );
                        seqOrder++;
                    }

                    tx.commit();
                } catch (const std::exception& e) {
                    // 트랜잭션 rollback: 상태 전이와 규칙 생성이 함께 취소됨
                    // rollback으로 상태가 원복되므로 별도 DRAFT 복원 알림 불필요
                    auditLog->logActivity(userId, fileId, "approval_failed",
                        std::string("Approval request creation failed: ") + e.what());
                    break;
                }

                // 5. commit 이후 알림 발송 (트랜잭션 밖 — 실패해도 요청 생성은 완료됨)
                // UNDER_REVIEW 상태 변경 알림 + approval_requested 알림을 한 번에 처리
                try {
                    workflowEngine->evaluateRules("tag_assigned", {
                        {"fileId", fileId}, {"tagName", TAG_UNDER_REVIEW}, {"userId", userId}
                    });
                    notifyStakeholders(fileId, "status_changed",
                        "Document status changed to " + std::string(TAG_UNDER_REVIEW) +
                        ": Approval requested: " + comment, {});
                } catch (const std::exception& e) {
                    auditLog->logActivity(userId, fileId, "notification_failed",
                        std::string("Status change notification failed: ") + e.what());
                }

                try {
                    std::vector<NotificationTarget> approverTargets;
                    for (const auto& approver : uniqueApprovers) {
                        approverTargets.push_back({approver,
                            {NotificationChannel::PUSH, NotificationChannel::EMAIL,
                             NotificationChannel::WEB}});
                    }
                    notifyStakeholders(fileId, "approval_requested",
                        "User " + userId + " requested your approval. Comment: " + comment,
                        approverTargets);
                } catch (const std::exception& e) {
                    auditLog->logActivity(userId, fileId, "notification_failed",
                        std::string("Approval request notification failed: ") + e.what());
                }

                success = true;
                break;
            }

            case ApprovalAction::APPROVE: {
                // APPROVE/REJECT 공통 로직을 processApprovalDecision으로 추출
                success = processApprovalDecision(
                    userId, fileId, comment,
                    DocumentStatus::APPROVED, TAG_APPROVED,
                    "Your document was approved",
                    "approved"
                );

                // 버그 수정: 이전 코드는 processApprovalDecision()이 "결정 기록 성공"으로
                //   true를 반환하면 무조건 document_approved 이벤트를 발생시켰음.
                //   → 3명 중 1명만 승인해도 (consensus=PENDING) document_approved 이벤트가
                //     발생하여 "문서가 승인됨" 알림이 잘못 나가는 로직 오류.
                // 수정: document_approved 이벤트 발생 제거.
                //   최종 승인 완료(consensus=APPROVED) 시의 알림은
                //   processApprovalDecision() 내부 commit 이후 블록에서 이미 처리됨.
                //   caller에서 중복 발생시키는 것은 불필요하며 오히려 오작동 유발.
                // [전환 시] processApprovalDecision()의 반환값을 bool 대신
                //   ApprovalDecisionResult { FAILED, RECORDED_PENDING, FINAL_APPROVED, FINAL_REJECTED }
                //   enum으로 변경하면 caller가 FINAL_APPROVED일 때만 후속 처리를 할 수 있음.
                break;
            }

            case ApprovalAction::REJECT: {
                // APPROVE/REJECT 공통 로직을 processApprovalDecision으로 추출
                success = processApprovalDecision(
                    userId, fileId, comment,
                    DocumentStatus::REJECTED, TAG_REJECTED,
                    "Your document was rejected",
                    "rejected"
                );
                break;
            }

            case ApprovalAction::CANCEL: {
                // 승인 요청 취소 (Q11=B+C 결정)
                //   권한: 요청자 본인 + 관리자 + 승인자 (셋 중 하나)
                //   REJECT와의 차이: 거절 사유 기록 없음. 행정적 무효화 처리.
                //   상태 전이: UNDER_REVIEW → DRAFT (재작업 가능)
                //   approval_rules.status: OPEN → CANCELLED
                success = cancelApprovalRequest(userId, fileId, comment);
                break;
            }

            // default case 추가 (setDocumentStatus와 동일한 방어 패턴)
            default:
                throw std::invalid_argument("Unknown ApprovalAction: " + std::to_string(static_cast<int>(action)));
        }

        return success;
    }

    // 승인 요청 취소 처리
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

        // 3~5. 규칙 상태 변경 + 취소 이력 + 문서 상태 복원: 트랜잭션으로 묶음
        // 버그 수정 1: approval_rules CANCELLED 후 approval_activity INSERT 실패 시
        //   rule은 닫혔는데 문서 상태가 UNDER_REVIEW에 머무는 정합성 오류 방지
        // 버그 수정 2: UNIQUE(rule_id, user_id) 충돌 방지
        //   같은 사용자가 이미 approved/rejected를 남긴 경우 'cancelled' INSERT가
        //   UNIQUE 제약에 걸려 실패하는 문제.
        //   해결: 기존 결정 기록이 있는 사용자의 경우 approval_activity INSERT를 건너뛰고
        //         audit_log에만 취소 이력을 남김.
        // [전환 시] @Transactional + setDocumentStatusInternal 사용으로 원자화
        auto now = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();
        std::string actorRole = isRequester ? "requester"
                              : isApprover  ? "approver"
                              : "admin";

        // 기존 결정 기록 여부 확인 (UNIQUE 충돌 방지용)
        auto existingDecision = db->query(
            "SELECT id FROM approval_activity "
            "WHERE rule_id = ? AND user_id = ? "
            "AND action IN ('approved', 'rejected') LIMIT 1",
            {ruleId, userId}
        );
        bool hasExistingDecision = !existingDecision.empty();

        try {
            TransactionGuard tx(*db);

            int affected = db->execute(
                "UPDATE approval_rules SET status = 'CANCELLED' "
                "WHERE id = ? AND status = 'OPEN'",
                {ruleId}
            );
            if (affected == 0) {
                // race condition: 다른 호출이 먼저 닫은 경우 — rollback 후 반환
                return false;
            }

            // 취소 이력 기록
            // 이미 결정 기록이 있는 경우 UNIQUE 충돌을 피하기 위해 INSERT 건너뜀
            // 취소 사실은 아래 audit_log와 알림으로 추적 가능
            if (!hasExistingDecision) {
                db->execute(
                    "INSERT INTO approval_activity "
                    "(rule_id, user_id, action, `timestamp`, comment) "
                    "VALUES (?, ?, 'cancelled', ?, ?)",
                    {ruleId, userId, std::to_string(now),
                     "Cancelled by " + actorRole +
                     (comment.empty() ? "" : ": " + comment)}
                );
            } else {
                // 기존 결정 기록 있음: audit_log에만 취소 사실 기록
                auditLog->logActivity(userId, fileId, "approval_cancelled_by_decider",
                    "Rule cancelled by " + actorRole + " who already decided. "
                    "Skipping approval_activity INSERT to avoid UNIQUE conflict.");
            }

            // 문서 상태 복원: UNDER_REVIEW → DRAFT (Internal: 알림 없음)
            // 버그 수정: 이전에는 복원 실패해도 commit하여
            //   approval_rules=CANCELLED, 문서=UNDER_REVIEW 불일치 상태가 발생했음.
            // 수정: 복원 실패 시 rollback → rule 취소와 문서 상태 복원이 원자적으로 처리됨.
            // (audit log도 같은 트랜잭션이므로 rollback 시 사라지지만,
            //  정합성이 audit log 보존보다 우선순위가 높음.
            //  Java 전환 시 REQUIRES_NEW 트랜잭션으로 로그를 별도 커밋할 것)
            if (!setDocumentStatusInternal(userId, fileId, DocumentStatus::DRAFT,
                                           "Approval request cancelled")) {
                auditLog->logActivity(userId, fileId, "approval_cancel_failed",
                    "Status revert to DRAFT failed — rolling back cancellation: " + ruleId);
                return false;  // tx 소멸자가 rollback: rule 취소도 함께 취소됨
            }

            tx.commit();
        } catch (const std::exception& e) {
            auditLog->logActivity(userId, fileId, "approval_cancel_failed",
                std::string("Cancel transaction failed: ") + e.what());
            return false;
        }

        // 6. commit 이후 알림 발송 (실패해도 취소 자체는 완료됨)
        try {
            notifyStakeholders(fileId, "approval_cancelled",
                "Approval request was cancelled by " + actorRole +
                " (" + userId + ")" +
                (comment.empty() ? "" : ". Reason: " + comment),
                {});
        } catch (const std::exception& e) {
            auditLog->logActivity(userId, fileId, "notification_failed",
                std::string("Cancel notification failed: ") + e.what());
        }

        return true;
    }

    // 관리자 권한 체크
    // 의사코드 99% 보강: 스텁 → 실제 DB 조회로 전환
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

    // Phase ②: 역할 부여 (관리자만 다른 사용자에게 ADMIN 부여 가능)
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

    // Phase ②: 역할 회수
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

        // 마지막 ADMIN 회수 차단
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

    // Phase ②: 모든 ADMIN 사용자 목록 (관리 UI용)
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

        // 4. 태그 갱신 + 이력 기록: TransactionGuard로 원자화 (isValidTransition 우회)
        // [수정] 기존 코드는 REPLACE INTO 성공 후 logDocumentChangeHistory 실패 시
        //        상태는 DRAFT인데 이력이 없는 불일치가 발생할 수 있었음.
        //        cancelApprovalRequest와 동일하게 TransactionGuard로 묶어 원자성 보장.
        // [Java 전환 시] @Transactional 단일 트랜잭션.
        //               logDocumentChangeHistory 내 workflowEngine->dispatchEvent()는
        //               @TransactionalEventListener(AFTER_COMMIT)으로 이동.
        auto draftTagRows = db->query(
            "SELECT id FROM systemtag WHERE name = ?", {std::string(TAG_DRAFT)}
        );
        if (draftTagRows.empty()) {
            auditLog->logActivity(adminUserId, fileId, "restore_failed",
                "systemtag 'draft' undefined — check initial data");
            return false;
        }
        std::string draftTagId = draftTagRows[0].at("id");

        try {
            TransactionGuard tx(*db);

            db->execute(
                "REPLACE INTO systemtag_object_mapping "
                "(objectid, objecttype, systemtagid) "
                "VALUES (?, 'files', ?)",
                {fileId, draftTagId}
            );

            logDocumentChangeHistory(adminUserId, fileId, "status_restored",
                "DEPRECATED -> DRAFT (admin restore). Reason: " + reason);

            tx.commit();
        } catch (const std::exception& e) {
            auditLog->logActivity(adminUserId, fileId, "restore_failed",
                std::string("Transaction failed — tag and history rolled back: ") + e.what());
            return false;
        }

        // 5. commit 이후 알림 (실패해도 복원 자체는 완료됨)
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

        // 4. 태그 갱신 + 이력 기록: TransactionGuard로 원자화 (isValidTransition 우회 — 의도된 예외)
        // [수정] restoreFromDeprecated와 동일한 이유로 TransactionGuard 추가.
        // [Java 전환 시] @Transactional. workflowEngine->dispatchEvent()는 AFTER_COMMIT으로 이동.
        auto draftTagRows = db->query(
            "SELECT id FROM systemtag WHERE name = ?", {std::string(TAG_DRAFT)}
        );
        if (draftTagRows.empty()) {
            auditLog->logActivity(userId, fileId, "revert_failed",
                "systemtag 'draft' undefined — check initial data");
            return false;
        }
        std::string draftTagId = draftTagRows[0].at("id");
        std::string actor = isOwner ? "owner" : "admin";

        try {
            TransactionGuard tx(*db);

            db->execute(
                "REPLACE INTO systemtag_object_mapping "
                "(objectid, objecttype, systemtagid) "
                "VALUES (?, 'files', ?)",
                {fileId, draftTagId}
            );

            logDocumentChangeHistory(userId, fileId, "approved_reverted",
                "APPROVED -> DRAFT by " + actor + " (error correction). Reason: " + errorReason);

            tx.commit();
        } catch (const std::exception& e) {
            auditLog->logActivity(userId, fileId, "revert_failed",
                std::string("Transaction failed — tag and history rolled back: ") + e.what());
            return false;
        }

        // 5. commit 이후 알림
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
        // 중복 채널 dedup 추가 (PK file_id, user_id, channel 충돌 방어)
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

    // ============================================================
    // RD-SRS-9.9 푸시 토큰 등록/해제 (신규 추가)
    // ------------------------------------------------------------
    // 목적: attemptDelivery의 PUSH 채널이 notifications_pushhash에서 토큰을 읽는데,
    //       토큰을 등록하는 진입점이 없었음.
    //       클라이언트(앱)가 FCM/APNs 토큰을 발급받으면 이 메서드를 통해 서버에 등록해야
    //       PUSH 알림 발송이 가능하다.
    // 토큰 등록 흐름:
    //   앱 최초 실행 또는 토큰 갱신 → registerPushToken(userId, newToken)
    //   앱 로그아웃 또는 푸시 수신 거부 → deregisterPushToken(userId, token)
    // 토큰 중복/재사용 정책:
    //   - 같은 토큰이 다른 userId에 이미 등록된 경우 먼저 제거 후 재등록.
    //     (앱 재설치, 계정 전환 시 이전 사용자의 토큰이 새 사용자 기기에 전달될 수 있음)
    //   - 같은 (uid, token) 쌍이 이미 존재하면 중복 등록 방지 (silent return false).
    // [Java 전환 시] @Transactional로 step 1~3 원자화.
    //               토큰 암호화 저장 검토 (PII 성격 있음).
    //               @PreAuthorize("isAuthenticated()")로 본인 토큰만 등록 가능하게 제한.
    // ============================================================

    // 푸시 토큰 등록 (앱 시작 시 또는 토큰 갱신 시 호출)
    //   token: FCM Registration Token 또는 APNs Device Token
    //   반환: true = 신규 등록 성공, false = 이미 동일 등록 존재하거나 실패
    bool registerPushToken(const std::string& userId, const std::string& token) {
        if (userId.empty() || token.empty()) return false;

        // 1. 같은 토큰이 다른 uid에 이미 등록된 경우 먼저 제거
        //    (앱 재설치 후 계정 전환 시나리오: 이전 사용자 토큰 재사용 방지)
        db->execute(
            "DELETE FROM notifications_pushhash WHERE token = ? AND uid != ?",
            {token, userId}
        );

        // 2. 같은 (uid, token) 쌍이 이미 존재하면 중복 등록 불필요
        auto existing = db->query(
            "SELECT id FROM notifications_pushhash WHERE uid = ? AND token = ? LIMIT 1",
            {userId, token}
        );
        if (!existing.empty()) {
            return false;  // 이미 등록됨 — 오류 아님, 호출자는 무시 가능
        }

        // 3. 신규 등록
        // [Java 전환 시] step 1~3 전체를 @Transactional로 묶어 race condition 방지
        db->execute(
            "INSERT INTO notifications_pushhash (uid, token) VALUES (?, ?)",
            {userId, token}
        );
        auditLog->logActivity(userId, "", "push_token_registered",
            "Push token registered (length=" + std::to_string(token.size()) + ")");
        return true;
    }

    // 푸시 토큰 해제 (로그아웃 또는 푸시 수신 거부 시 호출)
    //   token: 해제할 토큰. 빈 문자열이면 userId의 모든 토큰 삭제 (기기 전체 해제)
    //   반환: true = 1건 이상 삭제 성공, false = 해당 토큰 없음
    bool deregisterPushToken(const std::string& userId, const std::string& token) {
        if (userId.empty()) return false;

        int affected;
        if (token.empty()) {
            // 모든 토큰 삭제 (로그아웃, 계정 탈퇴 시나리오)
            affected = db->execute(
                "DELETE FROM notifications_pushhash WHERE uid = ?",
                {userId}
            );
            if (affected > 0) {
                auditLog->logActivity(userId, "", "push_token_deregistered",
                    "All push tokens removed (count=" + std::to_string(affected) + ")");
            }
        } else {
            // 특정 토큰만 삭제
            affected = db->execute(
                "DELETE FROM notifications_pushhash WHERE uid = ? AND token = ?",
                {userId, token}
            );
            if (affected > 0) {
                auditLog->logActivity(userId, "", "push_token_deregistered",
                    "Push token removed (length=" + std::to_string(token.size()) + ")");
            }
        }
        return affected > 0;
    }

    // 명시적 구독 외 자동 이해관계자 조회 (Q2 = B 표준안 채택)
    // - 파일 소유자 (files_versions의 최초 user_id, 추후 owners 테이블 도입 시 그쪽 우선)
    // - 마지막 수정자 (files_versions에서 timestamp DESC 1번째)
    // - 진행 중인 승인 요청의 요청자/승인자 (approval_rules.status = 'OPEN')
    // - 채널은 기본값(PUSH+WEB) 적용. 사용자별 선호 채널은 향후 user_preferences 테이블에서 조회 예정
    // (Q4=B): eventType별 이해관계자 범위 차등 적용
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

        // Phase ③ (의사코드 99% 보강): eventType별 이해관계자 차등 적용
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
        // approval_completed/cancelled 이벤트 전용:
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
            // status='OPEN' 조건 없는 변형 사용
            //   완료/취소 시점에는 rule이 CLOSED/CANCELLED로 전환된 상태이므로
            addApprovalRequestersAny();
            addApprovalApproversAny();
            addOwner();
        } else if (eventType == "approval_progress") {
            addApprovalRequesters();
            addApprovalApprovers();
        } else if (eventType == "approved_reverted") {
            // approved_reverted는 이미 CLOSED된 APPROVED rule을 되돌리는 시점
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

    // ── 헬퍼: NotificationChannel ↔ 문자열 변환
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
    // Triggered by: 파일 변경 이벤트, 워크플로우 이벤트
    // targets 자동 결정 로직 추가
    //          자동 산출. 호환성을 위해 명시 지정도 그대로 동작
    // 자동 트리거 호출부에서 호출되도록 통합
    //   호출 위치: createInitialVersion, onDocumentModified, setDocumentStatus,
    //              processApprovalWorkflow(REQUEST), processApprovalDecision
    //   [Java 전환 시] @EventListener 기반으로 변경. 각 메서드는 이벤트만 발행하고
    //                  notifyStakeholders는 리스너로 분리
    // 중복 알림 방지 (dedup_key)
    //   동일 이벤트가 5분 내 같은 사용자에게 두 번 발생해도 무시 (UNIQUE INDEX 사용)
    // Outbox 패턴 적용
    //   채널별 즉시 발송 → outbox INSERT(PENDING) → 즉시 발송 시도 → 성공 시 SENT
    //   실패 시 PENDING 유지, processOutboxQueue가 재시도
    // ID 정책 개정 반영:
    //   - fileId는 UUID (documents.file_id)로 가정.
    //   - 알림 메시지에 표시할 "버전 N" 같은 사용자 표기는 호출자가
    //     revision_no를 별도로 문자열에 포함해 넘기는 것이 권장된다.
    //     (versionId UUID를 그대로 알림 본문에 노출하지 말 것)
    //   - notifications.object_id 컬럼은 VARCHAR이므로 UUID 그대로 저장 가능.
    //   - file_subscriptions.file_id 역시 UUID 수용.
    bool notifyStakeholders(const std::string& fileId,
                            const std::string& eventType,
                            const std::string& message,
                            const std::vector<NotificationTarget>& targets) {
        // ── 1. 대상 자동 결정 (Phase A-2)
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

        // 이벤트 timestamp를 루프 밖에서 한 번만 생성
        auto eventTimestamp = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();

        // ── 2. 사용자별 알림 처리
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

        // ── 3. 즉시 발송 시도 (Phase A-X, Q5=A 결정)
        //   PENDING 항목 중 방금 추가한 것들을 즉시 처리.
        //   실패한 것은 PENDING 유지 → processOutboxQueue가 백그라운드로 재시도
        flushOutboxImmediate(eventTimestamp);

        // ── 4. 활동 로그
        auditLog->logActivity("system", fileId, "notifications_sent",
            "Event: " + eventType
            + ", Recipients: " + std::to_string(effectiveTargets.size())
            + ", DedupSkipped: " + std::to_string(dedupSkipped));

        // ── 5. 배치 처리 트리거 자리표시자
        // shouldBatchNotifications()는 항상 true를 반환하고
        // scheduleBackgroundJob()은 실제 작업을 하지 않는 no-op stub이다.
        // 이 블록은 "Java 전환 후 @Scheduled가 processOutboxQueue()를 주기적으로
        // 호출하게 될 위치"를 표시하는 설계 마커(design marker)다.
        // C++ 의사코드에서 실질적인 재시도는 scheduledOutboxFlush()를 외부에서 수동 호출한다.
        // [Java 전환 시] 이 블록 전체를 제거하고 processOutboxQueue()에
        //               @Scheduled(fixedDelay = 60_000)을 직접 적용한다.
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
    //   [Java 전환 시] @Scheduled(fixedDelay=60000) 메서드로 변환.
    // ── 멀티 Worker 안전성 구현 (기존 TODO 완료)
    //   기존 구조: SELECT PENDING → 처리
    //     → Worker가 여러 개면 같은 row를 동시에 가져가 중복 발송 가능
    //   변경 구조: PENDING → PROCESSING (claim) → 처리 → SENT/DLQ/PENDING(retry)
    //     1. Worker 고유 ID(workerId)를 생성하여 row를 PROCESSING으로 UPDATE (원자적 claim)
    //     2. locked_by = workerId인 row만 조회하여 처리
    //     3. 성공: SENT / 재시도: PENDING(locked_by=NULL) / DLQ: DLQ
    //     4. stale lock 회수: 5분 이상 PROCESSING 유지 row → PENDING 복원
    //        (Worker 크래시 또는 네트워크 장애 시 복구)
    //   MariaDB의 UPDATE ... LIMIT은 지원하지만 ORDER BY + LIMIT UPDATE는
    //   서브쿼리가 필요하므로 LIMIT만 사용 (순서 보장 불필요, 처리만 되면 됨)
    //   [Java 전환 시] locked_by에 hostname:pid 또는 Spring application name 사용.
    //                 SELECT ... FOR UPDATE SKIP LOCKED도 동등한 대안.
    int processOutboxQueue() {
        auto now = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();

        // 0. stale lock 회수 (Worker 크래시 복구)
        //    5분(kStaleLockSeconds) 이상 PROCESSING 상태로 방치된 row를 PENDING으로 복원
        constexpr int64_t kStaleLockSeconds = 300;
        db->execute(
            "UPDATE notification_outbox "
            "SET status = 'PENDING', locked_by = NULL, locked_at = NULL "
            "WHERE status = 'PROCESSING' "
            "  AND locked_at IS NOT NULL AND locked_at <= ?",
            {std::to_string(now - kStaleLockSeconds)}
        );

        // 1. row claim: PENDING → PROCESSING (Worker 단위 원자적 claim)
        //    이 UPDATE가 성공한 row는 이 Worker만 처리 가능
        //    다른 Worker가 동시에 같은 UPDATE를 수행해도 각자 다른 row를 가져감
        //    (MariaDB InnoDB row-level lock에 의해 UPDATE가 직렬화됨)
        std::string workerId = generateUUID();
        int claimed = db->execute(
            "UPDATE notification_outbox "
            "SET status = 'PROCESSING', locked_by = ?, locked_at = ? "
            "WHERE status = 'PENDING' AND retry_after <= ? "
            "LIMIT 100",
            {workerId, std::to_string(now), std::to_string(now)}
        );

        if (claimed == 0) return 0;  // 처리할 항목 없음

        // 2. 본인(workerId)이 claim한 row만 조회
        auto pending = db->query(
            "SELECT id, notification_id, user_id, channel, payload, retry_count "
            "FROM notification_outbox "
            "WHERE status = 'PROCESSING' AND locked_by = ?",
            {workerId}
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
                        // 토큰 미등록 상태: PUSH 발송 불가
                        // success = true로 처리하는 이유:
                        //   토큰이 없으면 재시도해도 동일 결과이므로 retry는 무의미하다.
                        //   사용자가 registerPushToken()을 호출하기 전까지 해소 불가.
                        //   단, 발송 여부 추적이 가능하도록 audit log를 남긴다.
                        //   (기존 코드는 이 경우를 무음으로 성공 처리하여 추적 불가였음)
                        auditLog->logActivity(userId, outboxId, "push_skipped_no_token",
                            "PUSH outbox " + outboxId + " marked SENT without delivery: "
                            "no push token registered for user '" + userId + "'. "
                            "Token can be registered via registerPushToken().");
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
                    std::to_string(newRetryCount) + " attempts. Error: " + errorMsg);

                // DLQ 발생 시 관리자 알림
                // retryDlqNotification()으로 수동 재발송 가능
                try {
                    auto adminRows = db->query(
                        "SELECT user_id FROM user_roles WHERE role = 'ADMIN' LIMIT 5",
                        {}
                    );
                    for (const auto& admin : adminRows) {
                        std::string adminId = admin.at("user_id");
                        NotificationTarget adminTarget{adminId,
                            {NotificationChannel::WEB}};
                        notifyStakeholders("", "notification_dlq",
                            "Outbox " + outboxId + " moved to DLQ for user " +
                            userId + ". Channel: " + channelStr +
                            ". Error: " + errorMsg +
                            ". Use retryDlqNotification() to retry.",
                            {adminTarget});
                    }
                } catch (const std::exception& e) {
                    auditLog->logActivity("system", userId, "dlq_admin_notify_failed",
                        "Failed to notify admins of DLQ: " + std::string(e.what()));
                }
            } else {
                int64_t backoffSeconds = calculateBackoff(newRetryCount);
                db->execute(
                    "UPDATE notification_outbox "
                    "SET retry_count = ?, retry_after = ?, last_error = ?, "
                    "    status = 'PENDING', locked_by = NULL, locked_at = NULL "
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
    // ============================================================
    // RD-SRS-9.9: DLQ 사후 처리 API
    // ------------------------------------------------------------
    // DLQ(Dead Letter Queue)로 이동한 알림에 대한 관리자 조회·재발송 기능.
    // Outbox 패턴에서 3회 재시도 후 DLQ 전이까지는 구현되어 있었으나,
    // 이후 관리자가 확인하고 수동 재발송할 수단이 없었음.
    // ============================================================

    // DLQ 목록 조회 (관리자 전용)
    //   특정 userId의 DLQ 항목 또는 전체 DLQ 목록 반환.
    //   userId가 빈 문자열이면 전체 DLQ 조회 (관리자 대시보드용).
    std::vector<std::map<std::string, std::string>> getDlqItems(
            const std::string& adminId,
            const std::string& filterUserId = "",
            int limit = 50,
            int offset = 0) {
        if (!isAdmin(adminId)) {
            auditLog->logActivity(adminId, "", "dlq_access_denied",
                "Non-admin attempted DLQ list access");
            return {};
        }

        std::vector<std::map<std::string, std::string>> rows;
        if (filterUserId.empty()) {
            rows = db->query(
                "SELECT id, notification_id, user_id, channel, payload, "
                "       retry_count, last_error, created_at "
                "FROM notification_outbox "
                "WHERE status = 'DLQ' "
                "ORDER BY created_at DESC LIMIT ? OFFSET ?",
                {std::to_string(limit), std::to_string(offset)}
            );
        } else {
            rows = db->query(
                "SELECT id, notification_id, user_id, channel, payload, "
                "       retry_count, last_error, created_at "
                "FROM notification_outbox "
                "WHERE status = 'DLQ' AND user_id = ? "
                "ORDER BY created_at DESC LIMIT ? OFFSET ?",
                {filterUserId, std::to_string(limit), std::to_string(offset)}
            );
        }

        auditLog->logActivity(adminId, "", "dlq_list_queried",
            "DLQ list queried. filter=" + (filterUserId.empty() ? "(all)" : filterUserId) +
            " count=" + std::to_string(rows.size()));
        return rows;
    }

    // DLQ 항목 수동 재발송 (관리자 전용)
    //   outboxId: 재발송할 notification_outbox.id
    //   DLQ 항목을 PENDING으로 되돌려 processOutboxQueue()가 재처리하게 함.
    //   retry_count는 0으로 초기화하여 3회 재시도 기회를 다시 부여.
    bool retryDlqNotification(const std::string& adminId,
                               const std::string& outboxId) {
        if (!isAdmin(adminId)) {
            auditLog->logActivity(adminId, "", "dlq_retry_denied",
                "Non-admin attempted DLQ retry for outbox " + outboxId);
            return false;
        }

        // DLQ 상태 확인
        auto rows = db->query(
            "SELECT id, user_id, channel FROM notification_outbox "
            "WHERE id = ? AND status = 'DLQ' LIMIT 1",
            {outboxId}
        );
        if (rows.empty()) {
            auditLog->logActivity(adminId, "", "dlq_retry_not_found",
                "Outbox " + outboxId + " not found or not in DLQ status");
            return false;
        }

        auto now = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();

        // PENDING으로 복원 + retry_count 초기화 + lock 해제
        int affected = db->execute(
            "UPDATE notification_outbox "
            "SET status = 'PENDING', retry_count = 0, retry_after = ?, "
            "    last_error = NULL, locked_by = NULL, locked_at = NULL "
            "WHERE id = ? AND status = 'DLQ'",
            {std::to_string(now), outboxId}
        );

        if (affected > 0) {
            auditLog->logActivity(adminId, rows[0].at("user_id"),
                "dlq_retry_requested",
                "Outbox " + outboxId + " reset to PENDING for retry by admin " + adminId);
            return true;
        }
        return false;
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

    // ── A-10 헬퍼들

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
    // extractFolderPath: fileId(UUID)에 대응하는 폴더 경로 반환
    //   fileId는 UUID이므로 문자열에서 직접 경로 추출 불가.
    //   documents.current_path를 조회하여 실제 파일 경로에서 폴더를 추출한다.
    //   예: current_path = "/legal/2025/계약서.docx" → "/legal/2025/"
    //   FOLDER scope 정책 매칭에 사용.
    // [Java 전환 시] IFile.getParent().getPath()로 교체
    std::string extractFolderPath(const std::string& fileId) {
        if (fileId.empty()) return "";
        auto rows = db->query(
            "SELECT current_path FROM documents WHERE file_id = ? LIMIT 1",
            {fileId}
        );
        if (rows.empty() || !rows[0].count("current_path")) return "";
        const std::string& path = rows[0].at("current_path");
        auto pos = path.rfind('/');
        if (pos == std::string::npos) return "";
        return path.substr(0, pos + 1);  // trailing slash 포함 ("/legal/2025/")
    }

    // 사용자 명시 버전 삭제 (Phase A-10 결정 ⑤: 정책 위반 처리)
    //   일반 사용자: 적용 정책의 minDays 이내 버전은 삭제 거부
    //   관리자: forceDelete=true로 정책 우회 가능 (단, 항상 로그 기록)
    //   추가 시나리오: 개인정보 삭제 요청, 잘못 업로드된 파일 즉시 제거 등
    // [Java 전환 시] @PreAuthorize로 forceDelete 권한 분리. 감사 로그는 별도 테이블
    // ID 정책 개정 반영:
    //   - versionId는 UUID (CHAR(36)). versionId 문자열로 저장 경로를 조립하지 않음.
    //   - 파일 삭제 시 files_versions.storage_key를 SELECT한 뒤 그 경로로 deleteFile.
    bool deleteVersion(const std::string& userId,
                       const std::string& versionId,
                       bool forceDelete) {
        // 1. 버전 존재 확인 + 메타데이터 조회
        //    05/18 - storage_key도 함께 SELECT (4단계 삭제 시 사용)
        auto versionRows = db->query(
            "SELECT file_id, `timestamp`, user_id, storage_key FROM files_versions "
            "WHERE version_id = ? LIMIT 1",
            {versionId}
        );
        if (versionRows.empty()) {
            return false;
        }
        const std::string& fileId = versionRows[0].at("file_id");
        int64_t versionTimestamp  = std::stoll(versionRows[0].at("timestamp"));
        std::string storageKey    = versionRows[0].count("storage_key")
                                    ? versionRows[0].at("storage_key") : "";

        // 권한 체크 추가: 파일 소유자 또는 관리자만 삭제 가능
        //   기존: minDays 미위반 시 누구나 삭제 가능 → 보안 이슈
        //   해결: 파일의 최초 버전 작성자(=소유자) 또는 관리자만 허용
        //   주의: forceDelete의 관리자 체크는 별도 (minDays 우회용)
        // documents.owner_user_id를 우선 조회로 변경 권장 (TODO).
        //         현 의사코드에서는 기존 로직(최초 버전 작성자) 유지.
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

        // 3.5 현재 버전 삭제 방지
        // documents.current_version_id가 삭제되면 문서의 현재 상태 조회·수정·비교가 모두 깨진다.
        // 일반 삭제: 현재 버전이면 거부
        // forceDelete + 관리자: 허용하되 직전 버전으로 current_version_id 재설정
        auto currentRows = db->query(
            "SELECT current_version_id FROM documents WHERE file_id = ?",
            {fileId}
        );
        bool isCurrentVersion = !currentRows.empty() &&
                                 currentRows[0].at("current_version_id") == versionId;

        if (isCurrentVersion) {
            if (!forceDelete || !isAdmin(userId)) {
                auditLog->logActivity(userId, fileId, "version_delete_denied",
                    "Cannot delete current version " + versionId +
                    ". Use forceDelete (admin only) to override.");
                return false;
            }
            // 관리자 forceDelete: 직전 버전으로 current_version_id 재설정
            auto prevRows = db->query(
                "SELECT version_id, revision_no FROM files_versions "
                "WHERE file_id = ? AND version_id != ? "
                "ORDER BY `timestamp` DESC LIMIT 1",
                {fileId, versionId}
            );
            if (prevRows.empty()) {
                auditLog->logActivity(userId, fileId, "version_delete_denied",
                    "Cannot delete the only version of document " + fileId);
                return false;
            }
            db->execute(
                "UPDATE documents SET current_version_id = ?, current_revision_no = ? "
                "WHERE file_id = ?",
                {prevRows[0].at("version_id"), prevRows[0].at("revision_no"), fileId}
            );
            auditLog->logActivity(userId, fileId, "current_version_reset",
                "current_version_id reset to " + prevRows[0].at("version_id") +
                " after force-deleting " + versionId);
        }

        // 4. 삭제 실행
        // 현재: DB 먼저 삭제 → 파일 삭제. DB 성공 후 파일 삭제 실패 시 orphan file 발생.
        // 현재는 예외 catch 후 audit log로 추적 가능.
        // [Java 전환 시] DELETION_PENDING → 비동기 워커 삭제 → DELETED 상태 전이로 파일-DB 정합성 보장
        // version_diffs 캐시 정리 (ghost diff 방지)
        // 이 버전을 from 또는 to로 참조하는 diff 캐시를 먼저 제거.
        // Schema에 FK CASCADE가 없으므로 명시적 정리 필요.
        try {
            db->execute(
                "DELETE FROM version_diffs "
                "WHERE from_version_id = ? OR to_version_id = ?",
                {versionId, versionId}
            );
        } catch (const std::exception& e) {
            auditLog->logActivity("system", fileId, "diff_cleanup_failed",
                "version_diffs cleanup failed for " + versionId + ": " + e.what());
            // diff 정리 실패는 버전 삭제를 막지 않음 — 계속 진행
        }

        int affected = db->execute(
            "DELETE FROM files_versions WHERE version_id = ?",
            {versionId}
        );
        if (affected > 0) {
            if (!storageKey.empty()) {
                try {
                    fileStorage->deleteFile(storageKey);
                } catch (const std::exception& e) {
                    // 파일 삭제 실패: DB는 이미 삭제됐으므로 orphan file 발생
                    // 로그만 남기고 계속 진행 (파일은 별도 정리 잡 대상)
                    auditLog->logActivity("system", fileId, "version_file_delete_failed",
                        "DB row deleted but file deletion failed: " + storageKey +
                        " / " + e.what());
                }
            }
            auditLog->logActivity(userId, fileId, "version_deleted",
                "Version " + versionId + " deleted by " + userId);

            // 자동 트리거 추가 (③ 매트릭스의 version_deleted: 소유자 + 관리자 전원)
            // 삭제 성공 후 알림 실패가 전체 실패처럼 보이지 않도록 safeNotify 사용
            // [Java 전환 시] VersionDeletedEvent 발행으로 분리
            safeNotify(userId, fileId, "version_deleted",
                "Version " + versionId + " was deleted by " + userId,
                {}, "Version deleted notification failed");
            return true;
        }
        return false;
    }

    // ============================================================
    // RD-SRS-9.1 / 문서 이동·이름변경 API
    // ------------------------------------------------------------
    // 목적: file_id(UUID)는 평생 불변이며 경로 변경에 영향받지 않는다.
    //       documents.current_path만 갱신하면 모든 버전 이력·승인 기록이 유지된다.
    //       storage_key는 변경하지 않는다 (파일 복사/이동 없이 DB 변경만으로 완료).
    // 구분:
    //   renameDocument: 같은 폴더 내 파일명 변경 (경로의 마지막 세그먼트만 변경)
    //   moveDocument:   다른 폴더로 이동 (전체 경로 변경)
    //   두 메서드는 내부 로직이 동일하며 current_path 갱신과 이력 기록을 공유한다.
    // [Java 전환 시] @Transactional로 경로 갱신 + 이력 기록 원자화.
    //               PathChangedEvent 발행 → 구독자 알림 비동기 처리.
    //               FOLDER scope 정책 재평가(evaluatePolicy) 트리거 필요.
    // ============================================================

    bool renameDocument(const std::string& userId,
                        const std::string& fileId,
                        const std::string& newFileName) {
        if (userId.empty() || fileId.empty() || newFileName.empty()) return false;
        return changeDocumentPath(userId, fileId, newFileName, "renamed");
    }

    bool moveDocument(const std::string& userId,
                      const std::string& fileId,
                      const std::string& newPath) {
        if (userId.empty() || fileId.empty() || newPath.empty()) return false;
        return changeDocumentPath(userId, fileId, newPath, "moved");
    }

    // renameDocument / moveDocument 공통 구현
    bool changeDocumentPath(const std::string& userId,
                             const std::string& fileId,
                             const std::string& newPath,
                             const std::string& changeType) {
        // 1. 문서 존재 확인 + 현재 경로 조회
        auto docRows = db->query(
            "SELECT current_path, owner_user_id FROM documents WHERE file_id = ? LIMIT 1",
            {fileId}
        );
        if (docRows.empty()) {
            auditLog->logActivity(userId, fileId, "path_change_failed",
                "Document not found: " + fileId);
            return false;
        }
        const std::string oldPath   = docRows[0].at("current_path");
        const std::string ownerUserId = docRows[0].at("owner_user_id");

        // 2. 권한 체크: 소유자 또는 관리자만 경로 변경 가능
        if (userId != ownerUserId && !isAdmin(userId)) {
            auditLog->logActivity(userId, fileId, "path_change_denied",
                "User " + userId + " has no permission to " + changeType + " file " + fileId);
            return false;
        }

        // 3. 경로 변경 실행
        //    핵심 원칙: file_id 불변, storage_key 불변, current_path만 갱신
        auto now = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();
        int affected = db->execute(
            "UPDATE documents SET current_path = ?, updated_at = ? WHERE file_id = ?",
            {newPath, std::to_string(now), fileId}
        );
        if (affected == 0) return false;

        // 4. 변경 이력 기록 (RD-SRS-9.3)
        logDocumentChangeHistory(userId, fileId, "path_" + changeType,
            oldPath + " -> " + newPath);

        // 5. 이해관계자 알림 (commit 이후 후처리)
        try {
            notifyStakeholders(fileId, "path_changed",
                "Document " + changeType + " from " + oldPath +
                " to " + newPath + " by " + userId, {});
        } catch (const std::exception& e) {
            auditLog->logActivity("system", fileId, "notification_failed",
                changeType + " notification failed: " + std::string(e.what()));
        }

        auditLog->logActivity(userId, fileId, "document_" + changeType,
            oldPath + " -> " + newPath);
        return true;
    }

    // RD-SRS-9.10 헬퍼: 특정 버전이 보호 대상인지 확인
    //   보호 조건: 문서의 current_version_id이면서 문서 상태가 APPROVED인 버전
    //   이 버전은 maxVersions/maxDays 조건에 걸려도 삭제하지 않는다.
    //   [Java 전환 시] 법적 보존 플래그(legalHold), 태그 기반 보호 조건도 추가 가능
    bool isProtectedVersion(const std::string& fileId, const std::string& versionId) {
        auto rows = db->query(
            "SELECT d.current_version_id, st.name AS status "
            "FROM documents d "
            "LEFT JOIN systemtag_object_mapping m "
            "  ON m.objectid = d.file_id AND m.objecttype = 'files' "
            "LEFT JOIN systemtag st ON st.id = m.systemtagid "
            "WHERE d.file_id = ? LIMIT 1",
            {fileId}
        );
        if (rows.empty()) return false;

        bool isCurrentVersion = (rows[0].at("current_version_id") == versionId);
        bool isApproved = rows[0].count("status") &&
                          rows[0].at("status") == "approved";

        // 현재 버전이면서 APPROVED 상태인 경우 보호
        return isCurrentVersion && isApproved;
    }

    // RD-SRS-9.10: 문서 버전 관리 정책 구성 (보존 기간, 최대 버전 수 등)
    int applyVersionRetentionPolicy(const std::string& fileId,
                                    const RetentionPolicy& policy) {
        int deletedVersions = 0;

        // 1. 현재 정책 읽기 (maps to config versions_retention_obligation)
        // policyString을 로그에 활용 (원래 dead code였음)
        std::string policyString = policy.autoCleanup ? "auto" : "";
        if (policy.minDays > 0 && policy.maxDays > 0) {
            policyString = std::to_string(policy.minDays) + "/" + std::to_string(policy.maxDays);
        } else if (policy.maxDays > 0) {
            policyString = "auto/" + std::to_string(policy.maxDays);
        }
        // policyString은 아래 정리 작업 로그에서 사용됨

        // 2. 파일의 모든 버전 조회 (maps to Storage::getVersions)
        // version_id 컬럼 추가 조회 (삭제 시 고유 식별자로 사용)
        // storage_key 컬럼도 함께 조회 (실제 파일 삭제 시 경로 추론 없이 사용)
        auto versions = db->query(
            "SELECT version_id, file_id, `timestamp`, size, storage_key "
            "FROM files_versions WHERE file_id = ? ORDER BY `timestamp` DESC",
            {fileId});

        // 3. 보존할 버전과 삭제할 버전 결정 (maps to Expiration::getExpireList)
        // vector → unordered_set: 할당량 정리 시 중복 체크를 O(1)로 개선
        std::unordered_set<std::string> toDeleteSet;
        // 초 단위로 통일
        auto now = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();

        // 계층적 보존 전략 구현
        // 버전 나이 기반 간격 선택으로 수정 (기존: 작은 interval부터 순회하여 계층 무력화)
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

            // APPROVED 보호 체크 — maxVersions/maxDays 보다 앞서 실행
            // 현재 버전이면서 APPROVED 상태인 버전은 어떤 조건에도 삭제하지 않음
            if (isProtectedVersion(fileId, version.at("version_id"))) {
                lastKeptTimestamp = vTimestamp;
                continue;
            }

            // minDays 보호 — maxVersions/maxDays보다 먼저 실행
            // minDays 이내 버전은 어떤 조건(maxVersions, maxDays)에도 삭제하지 않음.
            // 이전에 maxVersions 이후에 있었으나, maxVersions의 continue가 이 체크를
            // 건너뛰어 minDays 내 버전도 삭제되는 버그가 있었음.
            if (policy.minDays > 0 && versionAge < (int64_t)policy.minDays * 86400) {
                lastKeptTimestamp = vTimestamp;
                continue;
            }

            // 최대 버전 수 체크 (minDays 보호 통과 후 실행)
            if (policy.maxVersions > 0 && versionCount > policy.maxVersions) {
                toDeleteSet.insert(version.at("version_id"));
                continue;
            }

            // 최대 보관 기간 체크 (minDays 보호 통과 후 실행)
            if (policy.maxDays > 0 && versionAge > (int64_t)policy.maxDays * 86400) {
                toDeleteSet.insert(version.at("version_id"));
                continue;
            }

            // 계층적 간격 체크
            // 버전 나이에 따라 적절한 간격을 선택하여 비교
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
            // 과삭제(over-delete) 방지:
            //           quota 초과가 실제보다 크게 판단됨 → 불필요한 추가 삭제 발생
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
                // size_t underflow 방지: 3개 미만이면 정리 대상 없음
                if (versions.size() >= 3) {
                    // int 캐스팅으로 unsigned underflow 방지
                    for (int i = static_cast<int>(versions.size()) - 1; i >= 2 && totalSize > quotaLimit; i--) {
                        auto versionId = versions[i].at("version_id");  // DB에서 직접 조회
                        // unordered_set::find로 중복 체크 O(1) (기존: std::find O(n))
                        if (toDeleteSet.find(versionId) == toDeleteSet.end()) {
                            toDeleteSet.insert(versionId);
                            totalSize -= std::stoull(versions[i].at("size"));
                        }
                    } // end for
                } // end of versionsSize >= 3 check
            }
        }

        // 5. 버전 삭제 실행 (maps to Storage::expire)
        // versionId 문자열에서 저장 경로를 추론하지 않고
        //         앞서 SELECT한 결과에서 storage_key를 룩업한다.
        //         versions 벡터를 한 번 더 순회하여 매핑을 미리 만들어둔다.
        std::unordered_map<std::string, std::string> versionIdToStorageKey;
        for (const auto& v : versions) {
            std::string sk = v.count("storage_key") ? v.at("storage_key") : "";
            versionIdToStorageKey[v.at("version_id")] = sk;
        }

        for (const auto& versionId : toDeleteSet) {
            // 파일 시스템에서 삭제
            //   기존: deleteFile("files_versions/" + versionId)
            //   변경: storage_key 컬럼 값을 그대로 사용 (05/18)
            auto it = versionIdToStorageKey.find(versionId);
            if (it != versionIdToStorageKey.end() && !it->second.empty()) {
                // 파일 먼저 삭제 후 DB 삭제: 파일 없음 = 데이터 일관성 문제
                // [전환 시] DB에 DELETION_PENDING 상태 → 비동기 워커가 파일/DB 삭제 처리 권장
                try {
                    fileStorage->deleteFile(it->second);
                } catch (const std::exception& e) {
                    // 파일 삭제 실패: DB 삭제 건너뜀 (파일-DB 역전 불일치 방지)
                    auditLog->logActivity("system", fileId, "version_file_delete_failed",
                        "File deletion failed, skipping DB delete: " + it->second +
                        " / " + e.what());
                    continue;  // DB 삭제 skip — 다음 정리 사이클에서 재시도
                }
            }

            // version_diffs 캐시 정리 (ghost diff 방지)
            try {
                db->execute(
                    "DELETE FROM version_diffs "
                    "WHERE from_version_id = ? OR to_version_id = ?",
                    {versionId, versionId}
                );
            } catch (...) { /* diff 정리 실패는 무시하고 계속 진행 */ }

            // DB에서 삭제 (파일 삭제 성공 후에만 실행)
            // version_id 기반 삭제 (정확히 해당 버전만 삭제)
            try {
                db->execute("DELETE FROM files_versions WHERE version_id = ?", {versionId});
            } catch (const std::exception& e) {
                // DB 삭제 실패: 파일은 이미 삭제됐으므로 ghost row 발생
                auditLog->logActivity("system", fileId, "version_db_delete_failed",
                    "File deleted but DB delete failed: " + versionId +
                    " / " + e.what());
                continue;
            }

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
    // ───── 자동 실행 인프라 (b) - Java 전환 시 구현 ─────
    //   메서드별 권장 스케줄 (의사코드 표현):
    //     scheduledOutboxFlush       : 1분마다 실행
    //         [Java 전환 시] @Scheduled(fixedDelay = 60_000)
    //     scheduledRetentionCleanup  : 매일 새벽 02시 실행
    //         [Java 전환 시] @Scheduled(cron = "0 0 2 * * ?")
    //     scheduledExpiredDelegationsCleanup : 매시간 실행
    //         [Java 전환 시] @Scheduled(cron = "0 0 * * * ?")
    //     scheduledOldNotificationsCleanup : 매주 일요일 03시 실행
    //         [Java 전환 시] @Scheduled(cron = "0 0 3 ? * SUN")
    //   현재 의사코드에서는 자동 호출 인프라가 없으므로, 외부 CLI/cron이
    //   주기적으로 이 메서드들을 호출한다고 가정. 실제 동작은 Java 전환 시.
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

    // Deprecated:
    //   기존 versionId = "{fileId}.v{timestamp}_{counter}" 생성에 사용되던 멤버 카운터.
    //   새 ID 정책(version_id = UUID)에서는 사용하지 않는다.
    //   다음 이유로 운영 환경에서 부적합:
    //     - 일반 int이므로 멀티스레드 환경에서 ++가 원자적이지 않음
    //     - 프로세스 메모리에만 존재하므로 서버 재시작 시 0으로 초기화 → 직전 세션 ID와 충돌 가능
    //     - 다중 인스턴스 환경에서는 각 인스턴스가 별도 카운터를 가져 인스턴스 간 충돌 발생
    //   호환성을 위해 필드는 남겨두되, createInitialVersion/onDocumentModified에서는
    //   더 이상 참조하지 않는다. 향후 정리 시 제거 예정.
    int versionCounter = 0;

    // 프로토타입용 UUID 생성기
    //   주의: 본 함수는 C++ 의사코드 단계에서 ID 충돌 가능성을 낮추기 위한
    //        임시 구현이며, RFC 4122 표준을 따르는 진짜 UUID가 아니다.
    //        실제 운영에서는 검증된 UUID 라이브러리(libuuid, boost::uuids)
    //        또는 Java 전환 후 java.util.UUID.randomUUID()를 사용해야 한다.
    //   현재 한계 (발표 시 명시):
    //     - timestamp + 카운터 조합이므로, 멀티스레드 환경에서는
    //       uuidCounter++가 비원자적이라 충돌 가능
    //     - 같은 머신의 다른 프로세스 간에도 충돌 가능
    //     - 분산 환경에서는 머신별 카운터가 겹칠 수 있음
    //   → DB의 UNIQUE 제약 (files_versions.PK, uq_file_revision)이
    //      마지막 안전망 역할을 한다.
    //   형식: "uuid_{timestamp}_{counter}"
    //         예: "uuid_1715990400_42"
    //         실제 UUID(36자)는 아니지만 의사코드 단계에서 식별자 역할은 수행.
    //         스키마 CHAR(36)에는 들어가지만, 실 운영 시 RFC 4122로 교체 필요.
    std::string generateUUID() {
        // timestamp + 카운터로 빠른 연속 생성에서도 고유성 보장
        return "uuid_" + std::to_string(std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count()) + "_" + std::to_string(uuidCounter++);
    }

    // 03/18 수정: versionId에서 타임스탬프 추출
    //   기존 포맷: "fileId.v{timestamp}_{counter}" → "{timestamp}" 반환
    // Deprecated:
    //   05/18 ID 정책 개정으로 versionId는 UUID가 되었다.
    //   더 이상 versionId 문자열에서 timestamp/카운터를 추출할 수 없다.
    //   (UUID는 의미 없는 식별자이며, 그것이 본래 의도이다.)
    //   현재 코드에서 호출하는 곳은 없으며, 호환성 위해 정의만 남겨둔다.
    //   timestamp가 필요한 경우 다음과 같이 DB에서 조회해야 한다:
    //     SELECT `timestamp` FROM files_versions WHERE version_id = ?
    //   향후 정리 시 본 함수는 제거 예정.
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

    // APPROVE/REJECT 공통 로직 추출
    // 두 case의 구조가 거의 동일하여 코드 중복 제거 목적으로 분리
    // 차이점: DocumentStatus, TAG 상수, 알림 텍스트만 다름
    // APPROVE 전용 후처리(workflowEngine)는 호출부에서 처리
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
        // [Java 전환 시] newStatus/notifySubject 파라미터 제거, 시그니처 단순화
        (void)newStatus;
        (void)notifySubject;

        // TODO(Java 전환 시 — 승인 결정 완전 단일 트랜잭션화):
        //   현재 C++ 프로토타입은 두 단계 트랜잭션 구조임:
        //     Transaction 1: approval_activity INSERT + counter UPDATE → commit
        //     Transaction 2: setDocumentStatusInternal + approval_rules CLOSED → commit
        //   Transaction 1 commit 이후 Transaction 2가 실패하면 결정 기록과 카운터는 남았는데
        //   문서 상태는 변경되지 않는 불일치가 생길 수 있음.
        //   또한 중복 결정 확인(priorDecision check)과 순차 승인 확인(sequence check)이
        //   트랜잭션 밖에서 수행되어, 동시 요청 시 경합 가능성이 남아 있음.
        //   (approval_activity UNIQUE(rule_id, user_id) 제약이 DB 차원 방어로 추가됨)
        //   Java 전환 시 권장 구조:
        //     @Transactional
        //     → approval_rules SELECT ... FOR UPDATE  // 전체 흐름 row lock
        //     → rule OPEN 상태 재확인
        //     → 중복 결정 확인 (트랜잭션 안)
        //     → 순차 승인 차례 확인 (트랜잭션 안)
        //     → approval_activity INSERT
        //     → approval_rules counter UPDATE
        //     → evaluateConsensus()
        //     → (합의 도달 시) setDocumentStatusInternal()
        //     → (합의 도달 시) approval_rules status = CLOSED
        //     → commit
        //     → commit 이후 알림/워크플로우 트리거

        // 1. 승인/거절 권한 확인
        // status='OPEN' 조건 추가: 이미 처리 완료(CLOSED)된 규칙은 매칭하지 않음
        auto approverCheck = db->query(
            "SELECT rule_id FROM approval_rule_approvers "
            "WHERE entity_id = ? AND rule_id IN "
            "(SELECT id FROM approval_rules WHERE tag_pending = ? AND file_id = ? AND status = 'OPEN')",
            {userId, TAG_UNDER_REVIEW, fileId}
        );

        // Phase ① 위임 권한 체크 (의사코드 99% 보강)
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

        // (결정 ④): 같은 승인자 재결정 차단
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

        // SEQUENTIAL 모드의 경우 본인 차례인지 검증
        //   sequence_order가 작은 순서부터 진행. 본인보다 앞 순서가 모두 결정된 경우만 통과.
        // 위임 시나리오 보강: 위임받은 경우 위임자(delegatedFor)의 차례를 검사
        //   문제: SEQUENTIAL+위임 시 위임받은 본인은 승인자 명단에 없어
        //         isUserTurnInSequence가 myOrder.empty()로 항상 false 반환 → 결정 영구 거부
        //   해결: delegatedFor 있으면 위임자의 sequence_order 기준으로 차례 판단
        std::string turnCheckUser = delegatedFor.empty() ? userId : delegatedFor;
        if (!isUserTurnInSequence(ruleId, turnCheckUser)) {
            auditLog->logActivity(userId, fileId, "approval_out_of_sequence",
                "User " + userId + " attempted decision out of sequence on rule " + ruleId);
            return false;
        }

        // 2~3. 결정 기록 + 카운터 갱신 + 합의 평가: 트랜잭션으로 묶음
        // 이유: activity INSERT 성공 후 approval_rules UPDATE가 실패하면
        //   카운터가 실제 결정 수보다 적어 합의 판정이 영구적으로 틀려짐
        // 4순위 (위임 승인 user_id 수정):
        //   이전: approval_activity.user_id = 실제 버튼을 누른 userId (위임자)
        //         → SEQUENTIAL 판정 시 "원래 승인자 A가 결정했는지" 볼 때 B만 보여 오판
        //   수정: effectiveUser = 원래 승인자(delegatedFor), 실제 수행자는 comment에 기록
        //         이렇게 하면 SEQUENTIAL 순서 체크가 원래 승인자 기준으로 정상 동작
        //   [전환 시] actual_user_id / effective_approver_id 컬럼 분리로 근본 해결
        auto timestamp = std::to_string(std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()).count());

        // effective_user: 합의 판정과 SEQUENTIAL 순서 체크의 기준이 되는 승인자
        std::string effectiveUser = delegatedFor.empty() ? userId : delegatedFor;
        std::string actualActor   = userId;
        std::string activityComment = comment;
        if (!delegatedFor.empty()) {
            activityComment = "[actual actor " + actualActor +
                              ", delegated for " + delegatedFor + "] " + comment;
        }

        std::string consensusResult;
        try {
            TransactionGuard tx(*db);

            // approval_activity에 effectiveUser 저장 (SEQUENTIAL 체크 기준)
            db->execute("INSERT INTO approval_activity (rule_id, user_id, action, "
                        "`timestamp`, comment) VALUES (?, ?, ?, ?, ?)",
                        {ruleId, effectiveUser, actionTag, timestamp, activityComment});

            // received_approvals/received_rejections 카운터 갱신
            // AND status='OPEN' 조건 추가: CLOSED/CANCELLED된 rule에 카운터가
            // 증가하는 것을 방지. approval_activity INSERT와 같은 트랜잭션이지만
            // 극히 짧은 경합 구간에서 다른 요청이 rule을 닫을 수 있으므로 방어.
            bool isApprove = (std::string(actionTag) == "approved");
            int counterAffected = 0;
            if (isApprove) {
                counterAffected = db->execute(
                    "UPDATE approval_rules SET received_approvals = received_approvals + 1 "
                    "WHERE id = ? AND status = 'OPEN'", {ruleId}
                );
            } else {
                counterAffected = db->execute(
                    "UPDATE approval_rules SET received_rejections = received_rejections + 1 "
                    "WHERE id = ? AND status = 'OPEN'", {ruleId}
                );
            }
            if (counterAffected == 0) {
                // rule이 이미 닫힌 상태 — rollback 후 중복 결정으로 처리
                auditLog->logActivity(userId, fileId, "approval_rule_already_closed",
                    "Rule " + ruleId + " was closed before counter update");
                return false;  // tx 소멸자가 rollback (activity INSERT도 취소)
            }

            tx.commit();
        } catch (const std::exception& e) {
            auditLog->logActivity(userId, fileId, "approval_record_failed",
                std::string("결정 기록 실패: ") + e.what());
            return false;
        }

        // 3. 합의 평가 (트랜잭션 커밋 후 수행 — 최신 카운터 기준)
        consensusResult = evaluateConsensus(ruleId);

        if (consensusResult == "PENDING") {
            // 합의 미도달 → 다른 승인자 결정 대기. 상태 전이 없음.
            auditLog->logActivity(userId, fileId, "approval_decision_recorded",
                "User " + userId + " " + actionVerb + ", consensus pending");

            // 부분 결정도 요청자에게 알림 (진행 상황 공유)
            // 알림 실패가 결정 기록 성공을 가리지 않도록 safeNotify 사용
            safeNotify(userId, fileId, "approval_progress",
                "Decision recorded by " + userId + " (" + actionVerb + "). Consensus pending.",
                {}, "Approval progress notification failed");
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

        // 5. 상태 전이 + rule CLOSE: 트랜잭션으로 묶음
        // 이유: 상태는 APPROVED로 바뀌었는데 rule이 OPEN으로 남으면
        //   중복 승인 시도가 계속 들어올 수 있음
        std::string statusComment = static_cast<char>(std::toupper(
            static_cast<unsigned char>(finalActionVerb[0])))
            + finalActionVerb.substr(1) + " (consensus reached): " + comment;

        // setDocumentStatus는 내부적으로 DB를 직접 호출하므로,
        // approval_rules CLOSE와 묶기 위해 여기서 트랜잭션 시작
        // [전환 시] 이 블록 전체를 하나의 @Transactional 메서드로 감쌀 것
        try {
            TransactionGuard tx(*db);

            // 트랜잭션 안에서는 Internal 버전 사용 (알림/워크플로우 트리거 없음)
            // 이유: 트랜잭션 안에서 notifyStakeholders → flushOutboxImmediate가 실행되면
            //   rule close가 실패해 rollback되더라도 외부 알림이 이미 나간 상태가 됨
            // 알림은 commit 이후 아래에서 처리
            if (!setDocumentStatusInternal(userId, fileId, finalStatus, statusComment)) {
                auditLog->logActivity(userId, fileId, "approval_" + finalActionVerb + "_failed",
                    "Status transition to " + std::string(finalActionTag) + " failed");
                return false;  // tx 소멸자가 rollback 수행
            }

            // 7. 승인 규칙 종료 (OPEN → CLOSED) — 상태 전이와 반드시 함께 커밋
            // AND status='OPEN' 조건: cancelApprovalRequest와 경합 시
            //   이미 CANCELLED된 rule을 CLOSED로 덮어쓰는 것을 방지
            int closeAffected = db->execute(
                "UPDATE approval_rules SET status = 'CLOSED' "
                "WHERE id = ? AND status = 'OPEN'",
                {ruleId}
            );
            if (closeAffected == 0) {
                // rule이 이미 닫힌 상태 (CANCELLED 등) — setDocumentStatusInternal도 rollback
                auditLog->logActivity(userId, fileId, "approval_finalize_skipped",
                    "Rule was no longer OPEN during finalization: " + ruleId);
                return false;  // tx 소멸자가 rollback (상태 변경도 취소)
            }

            tx.commit();
        } catch (const std::exception& e) {
            auditLog->logActivity(userId, fileId, "approval_finalize_failed",
                std::string("상태 전이/rule close 실패: ") + e.what());
            return false;
        }

        // commit 이후: 워크플로우/알림 트리거 (트랜잭션 밖 — 실패해도 승인 처리는 완료됨)
        {
            std::string tagName = (finalStatus == DocumentStatus::APPROVED)
                                  ? TAG_APPROVED : TAG_REJECTED;
            safeEvalAndNotify(userId, fileId, tagName, "status_changed",
                "Document status changed to " + tagName + ": " + statusComment);
        }

        // 6. 요청자에게 최종 알림
        // 알림 발송 경로 일관성 수정
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
            // 회의 결정: APPROVE에도 comment 포함
            if (!comment.empty()) {
                notifyBody += ". Comment: " + comment;
            }
            // sendNotification 직접 호출 → notifyStakeholders 통합 (Outbox 적용)
            std::vector<NotificationTarget> requesterTarget = {{
                requester[0]["entity_id"],
                {NotificationChannel::PUSH, NotificationChannel::EMAIL, NotificationChannel::WEB}
            }};
            safeNotify(userId, fileId, "approval_completed",
                finalSubject + ": " + notifyBody,
                requesterTarget, "Approval completion requester notification failed");
        }

        // 7. 승인 규칙 종료는 위 5번 트랜잭션 안에서 처리됨

        // 8. 다른 이해관계자에게 broadcast 알림 (Phase A-3 자동 트리거)
        // 위 6단계의 요청자 1대1 알림과는 별도
        std::string requesterId = requester.empty() ? "" : requester[0]["entity_id"];
        auto stakeholders = getDefaultStakeholders(fileId, "approval_completed");
        std::vector<NotificationTarget> broadcastTargets;
        for (const auto& t : stakeholders) {
            if (t.userId != requesterId && t.userId != userId) {  // 요청자/결정자 제외
                broadcastTargets.push_back(t);
            }
        }
        if (!broadcastTargets.empty()) {
            safeNotify(userId, fileId, "approval_completed",
                "File " + fileId + " was " + finalActionVerb + " by consensus",
                broadcastTargets, "Approval completion broadcast notification failed");
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

    // 파일의 현재 문서 상태를 태그 기반으로 조회
    // 반환: 현재 상태 태그 이름 (예: "draft", "approved")
    //       태그가 없으면 빈 문자열 (새 파일이거나 상태 미지정)
    // 5회 순회 쿼리 → 단일 JOIN 쿼리로 개선
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

    // 상태 전이 유효성 검사
    // DLP 보안 정책: 허용되지 않은 상태 전이를 API 레벨에서 차단
    // 매트릭스 확정 (4월 합의 사항 반영)
    //   결정 ① UNDER_REVIEW → DRAFT 허용 (CANCEL 흐름 정상 동작 보장, A-7 후속)
    //   결정 ② DEPRECATED → DRAFT 일반 불허, 관리자 권한으로만 (별도 메서드 restoreFromDeprecated)
    //   결정 ③ APPROVED → DRAFT 일반 불허, 오류 수정 한정 허용 (별도 메서드 revertApprovedToDraft)
    //   결정 ④ APPROVED → UNDER_REVIEW 불허 (재승인은 새 버전으로)
    //   결정 ⑤ REJECTED → DEPRECATED 허용 (포기 시나리오)
    //   결정 ⑥ stateTransitionConfig 객체로 커스터마이징 가능
    // 도입 기업 커스터마이징:
    //   StateTransitionConfig 객체를 setTransitionMatrix()로 채워주면 우선 사용.
    //   미주입 시 기본 매트릭스 사용 (아래 default).
    // 매트릭스:
    //   (없음)        → DRAFT, UNDER_REVIEW                새 파일 최초 상태 설정
    //   DRAFT         → UNDER_REVIEW, DEPRECATED            검토 요청 또는 폐기
    //   UNDER_REVIEW  → APPROVED, REJECTED, DRAFT           결정 또는 CANCEL 복귀
    //   APPROVED      → DEPRECATED                          승인 후 폐기만 일반 허용
    //   REJECTED      → DRAFT, DEPRECATED                   재작업 또는 포기
    //   DEPRECATED    → (전이 불가)                         관리자 메서드로만 복원
    bool isValidTransition(const std::string& currentTag, const std::string& newTag) {
        // 커스터마이징 매트릭스 우선 사용 (결정 ⑥)
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

    // 설계 마커 stub: notifyStakeholders의 배치 트리거 자리표시자
    // 의사코드에서 항상 true 반환. 실제 조건 평가 로직 없음.
    // [Java 전환 시] 이 메서드 자체가 필요 없음 — @Scheduled가 대체.
    bool shouldBatchNotifications() {
        return true;  // 의사코드 전용: 항상 배치 트리거 경로 진입
    }

    // 설계 마커 stub: 백그라운드 잡 스케줄링 자리표시자
    // 실제 작업을 수행하지 않는 no-op. jobName은 Java 전환 후 @Scheduled 메서드명에 대응.
    // [Java 전환 시] processOutboxQueue()에 @Scheduled(fixedDelay=60_000) 적용으로 대체.
    void scheduleBackgroundJob(const std::string& jobName) {
        (void)jobName;  // 의사코드 전용 no-op — Java @Scheduled가 실제 스케줄링 담당
    }

    void sendPushNotification(const std::string& token, const std::string& message) {
        // 푸시 알림 발송 (FCM/APNS)
        // 실제 구현에서는 푸시 서비스 API 호출
    }

    size_t getQuotaLimit() {
        // 사용자 할당량 조회
        return 10ULL * 1024 * 1024 * 1024;  // 예: 10GB (ULL 접미사로 오버플로우 방지)
    }

    // 버전 나이에 따른 계층적 보존 간격 결정
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

    // JSON 문자열 이스케이프 헬퍼
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

    // 버전 메타데이터 JSON 생성 헬퍼
    // 현재는 author 필드만 포함, DLP 연동 시 추가 필드 확장 예정
    // JSON 라이브러리 도입 전까지 escapeJsonString으로 안전하게 조립
    std::string buildVersionMetadataJson(const std::string& userId) {
        return "{\"author\":\"" + escapeJsonString(userId) + "\"}";
    }

    // parseJson: 단순 평면 JSON 객체 파서 (기존 stub 교체)
    // 지원: {"key": "value"} 형식의 평면 문자열/비문자열 키-값
    // 미지원: 중첩 객체 {"dlp": {...}} — DLP 연동 시 nlohmann/json 또는 Jackson으로 교체
    // [Java 전환 시] ObjectMapper.readValue(json, new TypeReference<Map<String,String>>(){})
    std::map<std::string, std::string> parseJson(const std::string& json) {
        std::map<std::string, std::string> result;
        if (json.empty() || json.front() != '{') return result;

        size_t pos = 1;

        auto skipWS = [&]() {
            while (pos < json.size() && std::isspace(static_cast<unsigned char>(json[pos])))
                ++pos;
        };

        // 이스케이프를 처리하며 '"' 안의 문자열 읽기 (여는 '"'은 호출 전 확인)
        auto readString = [&]() -> std::string {
            ++pos;  // 여는 '"'
            std::string s;
            while (pos < json.size() && json[pos] != '"') {
                if (json[pos] == '\\' && pos + 1 < json.size()) {
                    ++pos;
                    switch (json[pos]) {
                        case '"':  s += '"';  break;
                        case '\\': s += '\\'; break;
                        case 'n':  s += '\n'; break;
                        case 'r':  s += '\r'; break;
                        case 't':  s += '\t'; break;
                        case 'b':  s += '\b'; break;
                        case 'f':  s += '\f'; break;
                        default:   s += json[pos]; break;
                    }
                } else {
                    s += json[pos];
                }
                ++pos;
            }
            if (pos < json.size()) ++pos;  // 닫는 '"'
            return s;
        };

        while (pos < json.size()) {
            skipWS();
            if (pos >= json.size() || json[pos] == '}') break;
            if (json[pos] == ',') { ++pos; continue; }
            if (json[pos] != '"') break;

            std::string key = readString();

            skipWS();
            if (pos >= json.size() || json[pos] != ':') break;
            ++pos;
            skipWS();

            std::string value;
            if (pos < json.size() && json[pos] == '"') {
                value = readString();
            } else {
                // 숫자, bool, null 등 비문자열 값 — 문자열로 읽어 저장
                while (pos < json.size() && json[pos] != ',' && json[pos] != '}') {
                    if (!std::isspace(static_cast<unsigned char>(json[pos])))
                        value += json[pos];
                    ++pos;
                }
            }

            if (!key.empty()) result[key] = value;
        }
        return result;
    }
};