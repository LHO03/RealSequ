# DocumentVersionWorkflowAPI

Nextcloud 기반 문서 버전 관리 및 워크플로우 API의 C++ 의사 코드(Pseudo-code) 구현체입니다.

서버측 애플리케이션 서비스 계층으로 설계되었으며, Desktop/Web/Mobile 클라이언트에서 호출 가능한 API를 제공합니다.

## 프로젝트 개요

이 프로젝트는 Nextcloud 오픈소스 아키텍처를 기반으로, 문서 버전 관리(RD-SRS-9.x)와 DLP(Data Loss Prevention) 모듈(RD-SRS-5.x)을 분석·설계·개선하는 것을 목적으로 합니다. 실제 동작하는 코드가 아닌, Nextcloud의 동작 방식을 C++ 의사 코드로 표현하여 구조를 이해하고 개선점을 도출하는 데 초점을 맞추고 있습니다.

## 요구사항 매핑

| 요구사항 | 설명 | 함수 | 상태 |
|----------|------|------|------|
| RD-SRS-9.1 | 모든 문서는 고유한 버전 번호를 가져야 함 | `createInitialVersion()` | ✅ 구현 완료 |
| RD-SRS-9.2 | 문서 수정 시 버전 자동 업데이트 | `onDocumentModified()` | ✅ 구현 완료 |
| RD-SRS-9.3 | 변경 이력 (수정자, 시각, 내용, 이유) | `logDocumentChangeHistory()` | ⚠️ 변경 내용 미구현 |
| RD-SRS-9.4 | 이전/현재 버전 간 차이 비교 | `prepareVersionComparison()` | ✅ DiffService 추가 |
| RD-SRS-9.5 | 특정 시점 문서 버전 조회 | `getVersionsAtTime()` | ✅ 구현 완료 |
| RD-SRS-9.6 | 문서 상태 관리 (초안/검토중/승인/폐기) | `setDocumentStatus()` | ✅ 구현 완료 |
| RD-SRS-9.7 | 승인 워크플로우 및 프로세스 관리 | `processApprovalWorkflow()` | ✅ 구현 완료 |
| RD-SRS-9.9 | 문서 변경 시 이해관계자 자동 알림 | `notifyStakeholders()` | ⚠️ 수동 호출 방식 |
| RD-SRS-9.10 | 버전 관리 정책 (보존 기간, 최대 버전 수) | `applyVersionRetentionPolicy()` | ✅ 구현 완료 |

## 아키텍처

### 핵심 클래스

```
DocumentVersionWorkflowAPI          // 메인 API 클래스
├── VersionService                  // 버전 서비스
├── AuditLogService                 // Activity 및 Admin Audit 기록
├── DocumentStatusManager           // 문서 상태 관리
├── WorkflowEngine                  // 이벤트 디스패치 및 룰 평가
├── NotificationService             // 푸시/이메일/웹 알림 발송
├── PolicyManager                   // 버전 보존 정책 적용
├── FileStorage                     // 파일 읽기/쓰기/복사/삭제
├── DatabaseConnection              // DB 쿼리 실행
└── DiffService                     // 두 버전 간 diff 계산 (02/11 추가)
```

### Nextcloud 소스 코드 매핑

각 함수는 실제 Nextcloud 소스 코드의 어떤 부분에 대응하는지 주석으로 명시되어 있습니다.

| 의사 코드 | Nextcloud 소스 |
|-----------|----------------|
| `createInitialVersion()` | `apps/files_versions/lib/Storage.php (store)` |
| `onDocumentModified()` | `apps/files_versions/lib/Listener/FileEventsListener.php` |
| `logDocumentChangeHistory()` | `apps/activity/lib/Data.php (send)` |
| `prepareVersionComparison()` | `apps/files_versions/lib/Sabre/VersionFile.php (get)` |
| `setDocumentStatus()` | `lib/public/SystemTag/ISystemTagManager.php` |
| `processApprovalWorkflow()` | `apps/approval/lib/Service/RuleService.php` |
| `notifyStakeholders()` | `lib/private/Notification/Manager.php` |
| `applyVersionRetentionPolicy()` | `apps/files_versions/lib/Expiration.php` |

### 데이터 구조체

```
FileContent          // 파일 콘텐츠 및 메타데이터
VersionInfo          // 버전 레코드 정보
ActivityEntry        // 활동 로그 기록
DiffLine             // 개별 변경 줄 정보 (ADDED/DELETED/UNCHANGED)
DiffHunk             // 변경 블록 (GitHub의 @@ 영역)
DiffResult           // 전체 비교 결과
DiffInfo             // 버전 비교용 콘텐츠 쌍
RetentionPolicy      // 버전 보존 정책 설정
NotificationTarget   // 알림 수신자 정보
```

## 주요 개선 이력

### Week 1 (02/10) — 코드 분석 및 버그 수정

- **timestamp 단위 통일**: `system_clock::count()` (나노초) → `duration_cast<seconds>` (초 단위)로 모든 함수 통일
- **userId 저장 누락 수정**: DB INSERT에 `user_id` 컬럼 추가 + metadata에 `author` 이중 저장 (fallback 전략)
- **size_t underflow 방지**: `applyVersionRetentionPolicy()`에서 `int` 캐스팅으로 unsigned 언더플로우 차단
- **REJECTED 상태 추가**: `DocumentStatus` enum에 누락된 `REJECTED` case 추가 및 `default` 방어 코드
- **TOCTOU 방지**: `onDocumentModified()`에서 `copyFile` → `readFile` + `writeFile` 패턴으로 변경
- **PostgreSQL 호환성**: `ORDER BY ... LIMIT`을 서브쿼리로 대체
- **dead code 제거**: `policyString` 변수를 로그에 활용하도록 수정
- **구조체 일관성**: DB INSERT 시 직접 값 대신 `activity.userId` 등 구조체 필드 사용

### Week 2 (02/11) — DiffService 설계

- **DiffService 클래스 추가**: 서버 측에서 두 파일 버전 간 차이를 계산하는 전용 서비스
- **GitHub 스타일 diff 구조체**: `DiffLine`, `DiffHunk`, `DiffResult` 정의
- **이중 비교 전략**: 텍스트 파일은 LCS 기반 줄 단위 diff, 바이너리 파일은 SHA-256 해시 비교
- **보안 강화**: 클라이언트 측 diff (사이즈 기반 변경 감지) → 서버 측 diff (내용 기반 비교)로 전환하여 동일 사이즈 변조 공격 탐지 가능
- **DiffInfo 구조체 확장**: `diffResult` 필드 추가로 서버 계산 결과를 클라이언트에 전달

## 알려진 제한사항 및 TODO

### 아키텍처 이슈

- **트랜잭션 미구현**: 파일 쓰기, 복사, DB 저장이 독립 실행되어 데이터 불일치 가능성 존재
- **이벤트 리스너 미연동**: `onDocumentModified()`는 수동 호출 방식이며, Nextcloud의 `NodeWrittenEvent` 자동 트리거 메커니즘과 미연동
- **알림 자동 발송 미구현**: `notifyStakeholders()`는 수동 호출 방식이며, 이벤트 기반 자동 호출 필요

### 기능 이슈

- **변경 내용 기록 미완성** (RD-SRS-9.3): 현재 파일 사이즈 변경만 기록하며, 실제 내용 변경 diff는 미포함
- **DiffService 알고리즘 미구현**: 클래스 선언만 존재하며, LCS 알고리즘 구현은 Week 3 예정
- **권한 검증 부재**: `prepareVersionComparison()`에서 요청자의 파일 접근 권한을 검증하지 않음

## 개발 환경

### Docker 기반 Nextcloud 환경

이 의사 코드의 실제 동작을 검증하기 위한 Docker 개발 환경이 구성되어 있습니다.

| 컨테이너 | 역할 |
|----------|------|
| Nextcloud | 파일 버전 관리 서버 |
| MariaDB | 메타데이터 저장 DB |
| Redis | 캐시 및 세션 관리 |
| Adminer | DB 관리 웹 UI |

## 빌드

이 프로젝트는 의사 코드(pseudo-code)로서 직접 컴파일을 목적으로 하지 않습니다. C++17 문법을 기반으로 작성되었으며, 구조와 로직을 이해하기 위한 참조 코드입니다.

```
요구 표준: C++17
주요 헤더: <string>, <vector>, <map>, <chrono>, <optional>, <cstdint>
```

## 프로젝트 타임라인

| 단계 | 기간 | 내용 |
|------|------|------|
| **분석** | 2월–4월 | 코드 분석, 버그 수정, Diff 알고리즘 개선, DLP 기술 조사 |
| **설계** | 4월–6월 | 문서 관리 및 DLP 모듈 설계, 통합 아키텍처 |
| **구현** | 7월–1월 | 개선된 설계를 실제 프로그래밍 언어 코드로 전환 |

## 라이선스

이 프로젝트는 Nextcloud의 오픈소스 아키텍처를 참조하여 작성된 의사 코드입니다.
