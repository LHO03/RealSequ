# docversion — 5.x DLP와 9.x 문서 형상관리 서버

RD-SRS-5.1·5.2·5.4의 규칙 기반 민감 데이터 판별과 RD-SRS-9.x의 버전·승인·알림·보존 기능을 제공하는 Java 구현체입니다.
전체 요구사항별 담당 범위와 구현 상태는 [저장소 README](../README.md)를 참고하십시오.

5.5는 휴리스틱의 한계를 고려하여 인터페이스와 골격만 유지합니다. `HeuristicScanner`는 현재 판정에 참여하지 않습니다.
5.3은 리얼시큐 Client, 5.6·5.7은 리얼시큐 Web 담당입니다. 문서 내용 승인(9.7)은 외부 반출 승인(5.7)과 별개입니다.

## 구성과 기술

| 구성 | 역할 |
|---|---|
| `dlp-core` | Spring 비의존 탐지 API·규칙·검증기·마스킹 및 단위 시험 |
| `docversion-app` | Spring Boot API, 9.x 서비스, 인증·인가, DB 매퍼와 DLP 연계 |
| `dlp-eval` | TXT·DOCX·PDF 평가 문서, 정답표, 업로드·판정 측정 스크립트 |

Java 21, Spring Boot 3.3.5, MyBatis 3.0.3, MariaDB 10.11, Apache Tika 2.9.2를 사용합니다.
의존 방향은 `docversion-app → dlp-core`입니다. 스키마는 Flyway V1~V18로 관리합니다.
C++ 의사코드는 9.x 선행 업무 설계이며, 실제 실행 구성은 이 디렉터리의 Java 코드와 마이그레이션입니다.

## 실행

이 디렉터리에서 실행합니다. 호스트에는 Docker Engine과 Compose가 필요합니다.
Compose 구성은 Nextcloud 미연동 독립 실행이며, MariaDB·앱·Adminer·MailHog를 기동합니다.

```bash
docker compose up --build
```

| 서비스 | 주소 | 용도 |
|---|---|---|
| 앱 | http://localhost:8080 | HTTP API·시연 콘솔 |
| Adminer | http://localhost:8081 | Server `mariadb`, DB·계정·비밀번호 `nextcloud` |
| MailHog | http://localhost:8025 | 시연 이메일 수신 확인 |

기본 `demo` 프로필에서 사용자 테이블이 비어 있으면 다음 계정을 생성합니다.

| 계정 | 비밀번호 | 역할 |
|---|---|---|
| alice | alice123 | USER |
| bob | bob123 | USER |
| admin | admin123 | ADMIN |

운영 시 `SPRING_PROFILES_ACTIVE=prod`를 지정하면 데모 계정은 생성되지 않습니다.
자세한 설치 절차는 [INSTALL.md](INSTALL.md)에 있습니다.

```powershell
curl.exe http://localhost:8080/actuator/health
docker compose down
```

헬스 응답은 `{"status":"UP"}`입니다. `docker compose down`은 컨테이너를 정지하며 DB·저장소 볼륨은 유지합니다.

## 처리 흐름

### 5.x 민감 데이터 검사

1. 최초 업로드 또는 수정본 업로드로 버전을 저장합니다.
2. 버전 저장 후 FULL 검사 작업을 적재합니다.
3. `DlpScanWorker`가 본문을 확보하여 규칙 기반 검사를 수행합니다. 추출 텍스트는 diff와 공유합니다.
4. 수정 버전의 diff가 완료되면 추가된 줄이 있는 경우 DELTA 검사도 적재합니다.
5. 판정, 점수, 탐지 위치, 마스킹된 탐지값을 API로 조회합니다.

기본 규칙은 주민번호·카드·계좌·휴대전화·이메일 5종입니다. 체크섬과 주변 문맥 조건은 규칙에 따라 적용됩니다.
판정은 기본 임계값 50과 점수 합계를 비교하며, 패턴 존재 여부와 임계값 초과 여부는 다를 수 있습니다.

| 구분 | 값과 의미 |
|---|---|
| 검사 범위 | `FULL`: 버전 전체 판정용, `DELTA`: 추가 줄의 보조 검사 |
| 작업 상태 | `PENDING → PROCESSING → COMPLETED / FAILED` |
| 판정 | `SENSITIVE`, `NOT_SENSITIVE`, `UNDETERMINED` |

클라이언트는 FULL의 작업 상태와 판정을 함께 확인해야 합니다. 검사 대기·실패·판정 불가를 안전으로 간주하면 안 됩니다.
활성 규칙이 없으면 현재 엔진은 `UNDETERMINED`를 반환합니다.
FULL 워커는 기본 15초 주기로 최대 20건씩 순차 처리하므로, 현재 구조에서 즉시 판정 완료를 보장하지 않습니다.

### 9.x 버전과 승인

업로드는 새 경로이면 최초 버전을, 같은 소유자·경로이면 수정 버전을 생성합니다.
수정 시 문서 행 잠금 아래 리비전을 증가시키고 변경 이력과 이해관계자 알림을 기록합니다.
승인 요청은 현재 버전을 대상으로 고정되며, 열린 요청이 있는 동안 새 버전 업로드를 차단합니다.
승인 후 수정본을 올리면 수정본 초안으로 전환됩니다.

시연 순서: Alice 업로드 → 수정본 업로드 → 인접 버전 비교 → 검토중 전환 → Bob에게 승인 요청 → Bob 승인 → 알림 확인.
diff와 DLP 검사는 비동기이므로 각각의 작업 상태를 조회하여 완료를 확인합니다.

## 주요 API

`/api/auth/**`를 제외한 아래 업무 API는 로그인이 필요합니다. 행위자는 세션 신원으로 결정합니다.
문서 조회는 소유자·구독자·관리자, 변경은 해당 서비스의 소유권·승인 자격 검사에 따릅니다.

### 민감 데이터 판별

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/documents/{fileId}/versions/{versionId}/dlp?scope=FULL` | 버전 검사 상태·판정·탐지 항목 |
| GET | `/api/documents/{fileId}/dlp` | 문서의 버전별 검사 목록 |
| POST | `/api/documents/{fileId}/versions/{versionId}/dlp/rescan?scope=FULL` | 소유자의 재검사 요청 |
| GET | `/api/dlp/rules` | 규칙 요약·임계값 조회, ADMIN |
| POST | `/api/dlp/rules/reload` | DB 규칙 재적재, ADMIN |

`scope`는 `FULL` 또는 `DELTA`를 사용합니다. 규칙 생성·수정·삭제 API는 제공하지 않습니다.
Web 관리 기능과 DB 변경 또는 별도 API의 연동 계약이 필요합니다.
재검사는 같은 버전·범위의 검사 행을 초기화하므로 실행마다 별도 이력을 누적하는 방식은 아닙니다.

### 버전·이력·상태

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/documents/upload` | `file`, `folder`, 선택 `reason`으로 업로드 |
| POST | `/api/documents/{fileId}/versions` | 수정본 `file`, 선택 `reason` 업로드 |
| GET | `/api/documents/{fileId}/versions` | `targetTimestamp`, `limit`, `offset` 기반 조회 |
| GET | `/api/documents/{fileId}/versions/{versionId}/content` | 버전 파일 다운로드 |
| GET | `/api/documents/{fileId}/activity` | 변경 이력 |
| GET | `/api/documents/{fileId}/diff` | `fromVersionId`, `toVersionId`의 기존 비교 작업 조회 |
| POST | `/api/documents/{fileId}/diff/retry` | 실패한 기존 비교 작업 재시도 |
| GET · POST | `/api/documents/{fileId}/status` | 상태 조회·전이 |
| GET | `/api/documents/{fileId}/status-history` | 상태 이력 |

### 승인·알림·보존

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/documents/{fileId}/approval` | 승인 요청과 이력 |
| POST | `/api/documents/{fileId}/approval/request` | `approvers`, `mode=ALL/MAJORITY/SEQUENTIAL`, 선택 `comment` |
| POST | `/api/documents/{fileId}/approval/{approve\|reject\|cancel\|retract}` | 승인·반려·취소·번복 |
| GET · POST | `/api/approval/delegation` | 위임 조회·설정 |
| POST | `/api/approval/delegation/revoke` | 위임 해지 |
| GET | `/api/notifications` | 내 알림 |
| POST | `/api/notifications/{id}/read` | 읽음 처리 |
| GET | `/api/notifications/outbox` | 발송 작업 조회, ADMIN |
| POST | `/api/documents/{fileId}/subscribe` · `/unsubscribe` | 구독 관리 |
| GET · POST | `/api/retention/policies` | 정책 조회·생성, ADMIN |
| POST | `/api/retention/policies/{id}` · `/{id}/deactivate` · `/{id}/apply` | 정책 수정·비활성화·적용, ADMIN |

## 테스트와 평가

Java 21과 Maven이 필요합니다. 아래 명령은 `docversion/` 기준입니다.

```bash
mvn -pl dlp-core test
mvn test
```

첫 명령은 Docker 없이 탐지 엔진을 시험합니다. 전체 `mvn test`는 MariaDB Testcontainers를 사용하므로 Docker가 필요합니다.
테스트에서는 `docversion.scheduling.enabled=false`로 자동 스케줄링을 끄고 필요한 워커를 직접 호출합니다.
Docker API 호환 설정은 `docversion-app/src/test/resources/docker-java.properties`에 있습니다.

서버 기동 후:

```powershell
powershell -ExecutionPolicy Bypass -File .\docversion_test.ps1
cd dlp-eval
powershell -ExecutionPolicy Bypass -File .\measure-dlp.ps1
```

2026-09-05 검토에서 기존 Docker 비의존 테스트 74건이 통과했습니다.
엔진 47건은 규칙·검증기·마스킹·겹침·측정 결함 회귀를, 앱 27건은 추출 상한·MIME·diff 상한·예외 매핑·LIKE 이스케이프를 확인했습니다.
해당 검토에서 DB·HTTP 전체 통합 시험과 부하 시험은 재실행하지 않았습니다.

[DLP 평가 문서](dlp-eval/README.md)에는 2026-08-31 측정과 V18 수정 근거가 기록되어 있습니다.
현재 문서 25종·파일 63개를 제공하며, 최신 규칙에 대한 전체 평가 지표는 실제 측정 실행 결과로 확인해야 합니다.

## Flyway 마이그레이션

| 버전 | 내용 |
|---|---|
| V1 | 문서·버전·diff·활동 이력 |
| V2~V3 | 문서 상태와 승인 요청 |
| V4~V5 | 알림·아웃박스·구독·보존 정책 |
| V6~V7 | 사용자·역할·이메일 |
| V8~V9 | 다중 승인자와 위임 |
| V10~V13 | 경로 유일 제약, 승인 대상 버전, diff 작업 상태, 알림 중복 키 |
| V14 | DLP 규칙·키워드 스키마와 패턴 5종 |
| V15 | DLP 검사·탐지 항목과 버전 텍스트 캐시 메타데이터 |
| V16~V17 | DLP 점수 상한 해제와 계좌 규칙 수정 |
| V18 | 측정 결과를 반영한 계좌 오탐 감소와 Amex 카드 패턴 추가 |

이미 적용한 마이그레이션 파일을 수정하지 않고 새 번호로 변경을 추가합니다.
기존 버전의 `text_status=PENDING`만으로 DLP 작업이 자동 생성되지는 않으므로 기존 자료에 대한 별도 백필이 필요합니다.

## 알려진 제약

| 영역 | 남은 문제 |
|---|---|
| 검사 완결성 | 앞 500만 자만 검사하거나 일부 텍스트만 추출한 상태에서도 비민감 확정 가능 |
| 작업 적재 | 버전 커밋 후 DLP·diff 적재 실패 시 작업 자체가 누락되고 자동 복구되지 않을 수 있음 |
| 검사 결과 정합성 | 탐지 항목과 판정 저장의 트랜잭션 누락, 실행 중 재검사·stale 회수 경합 |
| 실시간성 | 15초 주기 순차 후처리이며 판정 완료 지연 상한 미검증 |
| 버전 비교 | 인접 버전만 자동 적재. 비연속 버전쌍은 조회·retry만으로 작업 생성 불가 |
| 시점 조회 | 이전 결과가 없으면 미래 버전을 반환하는 fallback 존재 |
| 승인 위임 | 대리인의 문서 열람 권한과 본인 승인자 겸임 시 대리 판정 처리 보완 필요 |
| 보존 | 폴더 구분자 경계, 중첩 정책 충돌, 수정값 검증 보완 필요 |
| 지원 형식 | HWP 텍스트 추출 미지원. 추출 불가 시 DLP는 판정 불가, diff는 해시 비교로 처리 |
| 파일 처리 | 기본 업로드 20MB, 요청 25MB. 전량 메모리 적재로 대용량·동시 접근 검증 필요 |
| 운영 | 고아 파일 정리, 다중 인스턴스 정합성, 세션 기반 웹 사용의 CSRF 보호 보완 필요 |

이 문서는 현재 구현 상태를 설명하며 전체 명세의 인수 완료를 선언하지 않습니다.
문서 갱신: 2026-09-06.
