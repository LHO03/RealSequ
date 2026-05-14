# DocumentVersionWorkflowAPI — 알파 시연 데모

박사님 알파 테스트 시연용 Java Spring Boot 버전. 의사코드(`DocumentVersionWorkflowAPI.cpp`, 3,497줄)의 **시나리오 1만** 동작하도록 이식.

## 시연 시나리오 (보고서 7.2.1)

```
사용자 A(alice)         사용자 B(bob)
   │
   ├─ 파일 업로드 ─────────────────▶ version_created 알림
   │
   ├─ 파일 수정 ───────────────────▶ version_updated 알림
   │
   ├─ 승인 요청 (approvers=[bob]) ─▶ approval_requested 알림 (bob에게)
   │                                  │
   │                                  ├─ 알림 확인
   │                                  │
   │                                  ├─ 승인 결정
   │                                  ▼
   ◀── approval_completed 알림 (alice에게)
```

## 빠른 시작

```bash
# 0. (최초 1회) Gradle Wrapper 생성 — Gradle 8.x 설치되어 있어야 함
gradle wrapper --gradle-version 8.5

# 1. DB 시작
docker-compose up -d

# 2. 애플리케이션 실행 (별도 터미널)
./gradlew bootRun

# 3. Postman 컬렉션 import 후 1번부터 순서대로 실행
#    postman/DocVer-Demo-Scenario1.postman_collection.json
```

DB 직접 조회: <http://localhost:8081> (Adminer, user=docver/pw=docver_pw/db=docver)

빌드 환경 확인:
- Java 17 이상 (`java -version`)
- Gradle 8.5 이상 (`gradle -v`)
- Docker + docker-compose

## 통합 테스트

Testcontainers 기반. **Docker Desktop 실행 필요**. Docker 없는 환경에서는 기본 비활성화됨.

```bash
# Docker Desktop 실행 중인 경우에만:
./gradlew test -PrunTests=true

# 특정 클래스만
./gradlew test -PrunTests=true --tests Scenario1IntegrationTest
```

테스트 구성 (총 28개):

| 파일 | 검증 영역 | 테스트 수 |
|---|---|---|
| `ContainerSetupSmokeTest` | 컨테이너 + 스키마 셋업 | 3 |
| `Scenario1IntegrationTest` | 시연 8단계 흐름 + 검토 회귀 + 에러 케이스 + 알림 읽음 | 12 |
| `ApiIntegrationTest` | REST 엔드포인트 + DTO 검증 + 예외 매핑 | 7 |
| `StateTransitionMatrixTest` | 상태 전이 매트릭스 (결정 12~17 일부) | 5 |
| `IntegrationTestBase` | 공통 베이스 (테스트 아님) | - |

**참고**: 시연 본체(`bootRun`)는 테스트 없이도 동작합니다. 회의 시연 직전 빠른 시동을 위해 테스트는 기본 비활성화 (`build.gradle`의 `if (!project.hasProperty('runTests')) enabled = false`).

회의 중 박사님이 "이게 실제로 동작합니까"라고 물으실 때 → `Scenario1IntegrationTest`의 happyPath 테스트 코드를 보여드리는 것이 가장 직접적인 답이 됩니다.

## 회의 시연

`DEMO_RUNBOOK.md` 참조 — MariaDB 띄우는 3가지 옵션 (Docker/로컬/Portable) + 8단계 시연 가이드 + 박사님 예상 질문 대응.

## 이식 범위

### 포함

| 의사코드 메서드 | Java 위치 | 비고 |
|---|---|---|
| `createInitialVersion` | `DocumentVersionService.createInitialVersion` | 동일 동작 |
| `onDocumentModified` | `DocumentVersionService.updateDocument` | DiffService 제외 |
| `setDocumentStatus` | `DocumentVersionService.updateStatus` (private) | 매트릭스 동일 |
| `processApprovalWorkflow` REQUEST | `DocumentVersionService.requestApproval` | THRESHOLD+1 고정 |
| `processApprovalWorkflow` APPROVE/REJECT + `processApprovalDecision` | `DocumentVersionService.decideApproval` | 위임/SEQUENTIAL 제외 |
| `notifyStakeholders` | `NotificationService.notifyStakeholders` | Outbox 제외 |
| `getDefaultStakeholders` | `NotificationService.getDefaultStakeholders` (private) | 이벤트 4종만 분기 |
| `getUserNotifications` / `markNotificationRead` / `getUnreadCount` | `NotificationController` 엔드포인트 | 동일 |

### 의도적으로 제외 (Java 전환 차후 단계)

- 합의 모델 다중 지원 (UNANIMOUS, SEQUENTIAL)
- 승인 위임 (`createDelegation`, `getActiveDelegatorsOf`)
- 매트릭스 우회 (`restoreFromDeprecated`, `revertApprovedToDraft`)
- 보존 정책 cascade + CRUD
- Outbox 패턴 + 백그라운드 잡
- DiffService 본체 + 6개 포맷 텍스트 추출
- CANCEL 액션
- 구독 관리

→ 의사코드 전체 검토 보고서의 (1)/(2) 유형 항목 그대로

## 의사코드 검토 단계 수정 반영

박사님께 보고드린 의사코드 검토(05/14) 중 다음 11건이 본 Java 코드에 반영됨:

1. **이벤트 타입 의미 오류**: `onDocumentModified` 자동 트리거를 `version_updated`로 (의사코드 #1과 동일)
2. **Outbox 우회**: APPROVE 요청자 알림을 `notifyStakeholders`로 통일 (#4와 동일)
3. **트랜잭션**: `@Transactional` 명시 (의사코드 line 323 "트랜잭션 처리 문제" 해소)

## 기술 스택

- Spring Boot 3.2.5
- Java 17
- JdbcTemplate (JPA 미사용 — 의사코드와 SQL 1:1 매핑 유지)
- MariaDB 10.11 LTS
- Lombok

## 디렉터리 구조

```
src/main/java/com/example/docver/
├── DocumentVersionDemoApplication.java
├── controller/      # REST 엔드포인트 (2개)
├── service/         # 비즈니스 로직 (2개)
├── repository/      # JdbcTemplate 기반 DB 접근 (5개)
├── model/           # 도메인 객체
├── dto/             # API 요청 DTO
├── exception/       # WorkflowException + GlobalExceptionHandler
└── config/          # (예약)

src/main/resources/
├── application.yml
└── db/
    ├── schema.sql   # 9개 테이블 자동 생성
    └── seed.sql     # 상태 태그 초기 등록
```
