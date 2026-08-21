# docversion-core

Nextcloud 기반 문서 관리 시스템의 **문서 형상관리 모듈**.
선행 C++ 의사코드(`DocumentVersionWorkflowAPI.cpp` / `Diffservice.h` / `Schema.sql`)를
**Spring Boot 3 + MyBatis + MariaDB**로 전환한 구현체다.

**대상 요구사항**: RD-SRS-9.1 ~ 9.10 (전 범위 구현 완료)

| 항목 | 내용 |
|---|---|
| 9.1 | 최초 버전 생성 |
| 9.2 | 문서 수정 시 자동 버전 생성 |
| 9.3 | 변경 이력 (변경자·시각·사유) |
| 9.4 | 버전 간 diff |
| 9.5 | 특정 시점 버전 목록 및 콘텐츠 열람 |
| 9.6 | 문서 상태 관리 |
| 9.7 | 승인 워크플로 (다중 승인자·위임 포함) |
| 9.9 | 알림 (인앱 + 이메일) |
| 9.10 | 보존 정책 |

> 9.8은 명세서상 존재하지 않는다 (9.7 다음이 9.9).

---

## 스택

| 구분 | 선택 |
|---|---|
| 언어 · 프레임워크 | Java 21, Spring Boot 3.3.5 |
| 데이터 접근 | MyBatis 3.0.3 — MariaDB 고유 구문(`FOR UPDATE`, `INSERT IGNORE`, `JSON_SET`) 직접 작성 |
| 데이터베이스 | MariaDB 10.11 LTS |
| 스키마 관리 | Flyway (현재 V13까지) |
| 인증 · 인가 | Spring Security 6.3 — 기본 거부(deny-by-default) |
| 텍스트 추출 | Apache Tika 2.9 (diff 대상 문서 본문 추출) |
| 테스트 | Testcontainers (실제 MariaDB 기동, H2 미사용) |

**MyBatis를 택한 이유**: 동시 편집·동시 결재 경합 제어가 핵심이라 잠금 구문을 직접 작성하고
검토해야 한다. SQL이 자동 생성되면 검토가 어렵다.

---

## 실행 (Docker만 필요)

호스트에 Docker만 있으면 된다. Java·Maven·IDE 불필요 — 앱은 컨테이너 안에서 빌드된다.

```bash
docker compose up --build
```

| 서비스 | 주소 | 용도 |
|---|---|---|
| app | http://localhost:8080 | API 서버 · 콘솔 화면 |
| Adminer | http://localhost:8081 | DB 조회 (Server `mariadb`, 계정 `nextcloud`/`nextcloud`) |
| MailHog | http://localhost:8025 | 발송된 알림 메일 확인 |
| MariaDB | localhost:3306 | 직접 접속용 (선택) |

기동 확인: `curl http://localhost:8080/actuator/health` → `{"status":"UP"}`

Flyway가 V1~V13을 순차 적용해 테이블 15개를 생성한다.

### 테스트 계정

기본 프로필 `demo`에서 자동 생성된다 (`users` 테이블이 비어 있을 때만).

| 계정 | 비밀번호 | 역할 |
|---|---|---|
| alice | alice123 | USER |
| bob | bob123 | USER |
| admin | admin123 | ADMIN |

### 정지 · 초기화

```bash
docker compose down       # 컨테이너만 정지 (데이터 유지)
docker compose down -v    # DB·저장소 볼륨까지 삭제 (완전 초기화)
```

---

## 시연 — 브라우저 콘솔

<http://localhost:8080> 접속. 로그인 후 전 기능을 화면에서 조작할 수 있다.

| 구역 | 가능한 작업 |
|---|---|
| 파일 업로드 | 문서 업로드 |
| 문서 목록 | 내 문서 조회 |
| 선택한 문서 | 수정본 업로드, 시점 조회, diff 비교·재시도, 상태 변경, 승인 요청·승인·반려·취소·번복, 구독 |
| 알림 | 내 알림, 읽음 처리, 아웃박스 확인 |
| 보존 정책 | 정책 생성·적용·비활성화 (ADMIN) |

**권장 시연 순서**

1. `alice`로 로그인 → 파일 업로드
2. 같은 문서에 수정본 업로드 → 리비전 2 생성 확인
3. 두 버전 선택 → **비교** → diff 결과 확인
   - 계산은 배경 워커가 수행한다. `PENDING`/`PROCESSING`이면 잠시 후 다시 누르면 된다.
   - `FAILED`이면 실패 사유와 함께 **재시도** 버튼이 표시된다.
4. 상태를 **검토중**으로 변경 → **승인 요청** (승인자 `bob`)
5. `bob`으로 로그인 → 승인 → 문서 상태가 **승인**으로 전이되는지 확인
6. 알림 구역에서 통지 확인, MailHog(<http://localhost:8025>)에서 메일 확인

> 활동 이력 전체(행위 종류·대상 버전 포함)는 API로 확인할 수 있다.
> ```bash
> curl -b cookie.txt "http://localhost:8080/api/documents/{fileId}/activity"
> ```

---

## DB로 확인하기 (Adminer)

<http://localhost:8081> → System `MySQL/MariaDB`, Server `mariadb`,
계정 `nextcloud` / `nextcloud`, Database `nextcloud`.

| 테이블 | 확인 포인트 |
|---|---|
| `files_versions` | 같은 `file_id`에 `revision_no` 1, 2… 단조 증가 |
| `version_diffs` | `status` 전이(PENDING → COMPLETED), `diff_method=myers`, 추가·삭제 줄 수 |
| `activity` | 변경 이력. `subjectparams`에 사유·versionId가 JSON으로 기록 |
| `files_versions.metadata` | `JSON_SET`으로 기록된 `{"author":…,"reason":…}` |
| `approval_requests` | `target_version_id` — 승인 대상 버전이 고정되어 있음 |
| `notifications` | `dedup_key` — 사건 식별자 기반 중복 방지 키 |

---

## 주요 API

모든 `/api/**`는 로그인이 필요하다. 작성자·행위자는 요청 값이 아니라 **세션 신원**으로 결정된다.

### 버전 (9.1 · 9.2 · 9.3 · 9.4 · 9.5)

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/documents/upload` | 업로드. 같은 경로면 새 버전, 새 경로면 새 문서 |
| POST | `/api/documents/{fileId}/versions` | 수정 → 새 버전 |
| GET | `/api/documents/{fileId}/versions` | 시점 기준 버전 목록 |
| GET | `/api/documents/{fileId}/versions/{versionId}/content` | 특정 버전 파일 다운로드 |
| GET | `/api/documents/{fileId}/activity` | 변경 이력 |
| GET | `/api/documents/{fileId}/diff` | 두 버전 차이 (상태 포함) |
| POST | `/api/documents/{fileId}/diff/retry` | 실패한 diff 재계산 |

### 상태 (9.6)

| 메서드 | 경로 |
|---|---|
| GET · POST | `/api/documents/{fileId}/status` |
| GET | `/api/documents/{fileId}/status-history` |

### 승인 (9.7)

| 메서드 | 경로 |
|---|---|
| GET | `/api/documents/{fileId}/approval` |
| POST | `/api/documents/{fileId}/approval/request` |
| POST | `/api/documents/{fileId}/approval/{approve\|reject\|cancel\|retract}` |
| GET · POST | `/api/approval/delegation` , `/api/approval/delegation/revoke` |

### 알림 (9.9)

| 메서드 | 경로 |
|---|---|
| GET | `/api/notifications` |
| POST | `/api/notifications/{id}/read` |
| GET | `/api/notifications/outbox` (ADMIN) |
| POST | `/api/documents/{fileId}/subscribe` · `/unsubscribe` |

### 보존 정책 (9.10) — ADMIN 전용

| 메서드 | 경로 |
|---|---|
| GET · POST | `/api/retention/policies` |
| POST | `/api/retention/policies/{id}` · `/deactivate` · `/apply` |

---

## 설계 요점

**계층 분리** — `web`(HTTP 창구) → `service`(업무 규칙) → `mapper`(SQL). 이름 끝 단어가 곧 계층이다.

**잠금 순서 고정** — 모든 변경 경로에서 `documents` → `approval_requests` 순으로 획득해 교착을 방지한다.

**이중 인가** — 경로 단위(Spring Security, 기본 거부) + 객체 단위(`DocumentAccessPolicy`).
로그인만으로 타인의 문서에 접근할 수 없다.

**부수효과 분리** — diff 계산·알림 발송은 `@TransactionalEventListener(AFTER_COMMIT)`와
배경 워커로 분리했다. 실패해도 본 작업(버전 생성)은 확정된 채로 남는다.

**승인 대상 버전 고정 (V11)** — 승인 요청 시점의 버전을 기록하고, 열린 요청이 있으면
새 버전 업로드를 차단한다. 결재자가 본 문서와 승인된 문서가 달라지지 않는다.

**diff 작업 상태 기계 (V12)** — PENDING → PROCESSING → COMPLETED / FAILED.
최대 3회 재시도, 죽은 워커 회수, 수동 재시도를 지원한다.

### 배경 워커

| 워커 | 하는 일 |
|---|---|
| `DiffJobWorker` | diff 계산 처리 |
| `NotificationOutboxWorker` | 알림 발송 (아웃박스 패턴) |
| `RetentionCleanupWorker` | 보존 정책 적용 |
| `DelegationCleanupWorker` | 만료 위임 해제 |

테스트에서는 `docversion.scheduling.enabled=false`로 일괄 비활성화된다.

---

## 테스트

### JUnit (25건)

```bash
mvn test
```

Docker 필요 — Testcontainers가 `mariadb:10.11`을 기동한다.

| 클래스 | 건수 | 검증 대상 |
|---|---|---|
| `ApprovalVersionIntegrityTest` | 9 | 승인 대상 버전 고정, 업로드 차단, 예외 분류 |
| `GlobalExceptionHandlerTest` | 5 | 예외 → HTTP 상태 매핑 (컨테이너 불필요) |
| `DiffJobStateTest` | 4 | 재시도, FAILED 확정, 점유 원자성 |
| `NotificationDedupTest` | 4 | 사건 식별자 기반 중복 제거 |
| `VersionLifecycleParityTest` | 2 | 리비전 증가, diff 적재, 정렬 |
| `VersionUpdatedMimeTest` | 1 | 이전 버전 MIME 전달 |

> **Docker Engine 29 이상 사용 시**: Testcontainers 1.x는 API 버전 협상에 실패한다.
> `src/test/resources/docker-java.properties`의 `api.version=1.44`가 이를 우회한다.
> 이 파일을 지우면 테스트가 기동하지 않는다.

### 통합 테스트 (76건)

서버가 기동된 상태에서 실행한다. 실제 HTTP 호출로 인증·인가까지 검증한다.

```powershell
powershell -ExecutionPolicy Bypass -File .\docversion_test.ps1
```

절 0~14로 구성되며 동시 판정 직렬화, 경로 경합, 접근 제어, 예외 분류, diff 상태 기계를 포함한다.

---

## 스키마 (Flyway V1~V13)

| 버전 | 내용 |
|---|---|
| V1 | `documents`, `files_versions`, `version_diffs`, `activity` |
| V2 | `document_status_history`, `documents.status` |
| V3 | `approval_requests`, `approval_activity` |
| V4 | `notifications`, `notification_outbox`, `file_subscriptions` |
| V5 | `retention_policies` |
| V6 | `users`, `user_roles` |
| V7 | `users.email` |
| V8 | `approval_request_approvers` (다중 승인자) |
| V9 | `approval_delegations` (위임) |
| V10 | 문서 경로 유일 제약 (동시 생성 경합 차단) |
| V11 | `approval_requests.target_version_id` (승인 대상 고정) |
| V12 | `version_diffs` 상태·재시도 컬럼 |
| V13 | 알림 중복 키 의미 변경 (사건 식별자 기반) |

**적용된 마이그레이션 파일은 수정하지 않는다.** Flyway가 체크섬을 검사해 기동을 거부한다.
변경이 필요하면 새 번호를 추가한다.

---

## C++ → Spring 매핑

| C++ | Java |
|---|---|
| `DocumentVersionWorkflowAPI` (버전 메서드) | `DocumentVersionService` (오케스트레이터) |
| `TransactionGuard` (RAII) | `VersionWriteService`의 `@Transactional` |
| `DatabaseConnection` (MySQL C API) | MyBatis 매퍼 + HikariCP |
| `FileStorage` | `StorageService` + `LocalFileStorage` |
| `DiffService` / `DocumentTextExtractor` | 동명 클래스 / 인터페이스 + Tika 구현 |
| `generateUUID` / `escapeJsonString`, `parseJson` | `UuidGenerator` / `VersionMetadata` (Jackson) |
| 후처리 `notifyStakeholders`, diff 캐시 | `@TransactionalEventListener(AFTER_COMMIT)` + 배경 워커 |

업무 규칙의 정본은 C++ 단계에 있다. Java로 옮기는 것은 트랜잭션·이벤트 같은 인프라 계층이며,
순수 업무 규칙에 결함이 발견되면 C++ 단계에서 먼저 수정한다.

---

## 알려진 제약 · 잔여 과제

| 우선순위 | 항목 |
|---|---|
| 높음 | 보존 정책 폴더 범위가 `LIKE` 접두 검색이라 `/a`로 `/abc`까지 포함한다 |
| 높음 | 보존 정책 우선순위 해석기(FILE > FOLDER > USER > GLOBAL) 미구현 |
| 중간 | 파일 전송이 전량 메모리 적재 방식 (`InputStream` 기반 전환 필요) |
| 중간 | 다중 인스턴스 배치 락 (ShedLock 또는 `SKIP LOCKED`) |
| 중간 | 고아 파일 정리 절차 부재 |
| 중간 | CI 미구축 (GitHub Actions) |
| 낮음 | Testcontainers 2.x 상향 시 `docker-java.properties` 제거 가능 |
| 낮음 | HWP 텍스트 추출 미지원 — 국제 라이브러리 부재로 해시 비교로 처리 |

**CSRF는 비활성화 상태다.** 클라이언트 에이전트가 호출하는 백엔드 API이므로 브라우저 쿠키
자동 전송에 기인하는 CSRF가 이 사용 형태에 해당하지 않는다는 판단이다. 세션 기반 웹 UI를
정식 제공하게 되면 재검토 대상이다.
