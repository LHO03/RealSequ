# 회의 시연 진행 매뉴얼

박사님 알파 테스트 시연용. 회의 전 5분 준비 → 회의 중 8단계 시연.

---

## 사전 준비 (회의 전 1회만)

### 1단계: MariaDB 띄우기 (3가지 옵션 중 1개 선택)

#### 옵션 A — Docker Desktop이 실행 중인 경우 (가장 권장)

```powershell
cd C:\Users\user\Desktop\ldH\DocumentWorkflowAPI\Alpha
docker-compose up -d
```

성공 확인:
```powershell
docker ps
# docver-mariadb, docver-adminer 두 컨테이너가 떠 있어야 함
```

장점: docker-compose.yml에 모두 설정됨. Adminer(<http://localhost:8081>)로 DB 직접 조회 가능.

#### 옵션 B — Docker 없이 로컬 MariaDB 사용

MariaDB Community를 Windows에 설치 (<https://mariadb.org/download/>).

설치 후 PowerShell에서:
```powershell
# MariaDB 콘솔 진입 (설치 시 정한 root 비밀번호 입력)
mysql -u root -p

# 데이터베이스/사용자 생성
CREATE DATABASE docver CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'docver'@'localhost' IDENTIFIED BY 'docver_pw';
GRANT ALL PRIVILEGES ON docver.* TO 'docver'@'localhost';
FLUSH PRIVILEGES;
EXIT;
```

장점: Docker 의존성 없음. 안정적.
단점: 설치 시간 ~15분.

#### 옵션 C — 빠른 임시 방안 (XAMPP/MariaDB Portable)

이미 다른 프로젝트로 MariaDB가 설치되어 있다면, `docver` 데이터베이스만 추가:

```sql
CREATE DATABASE docver CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'docver'@'localhost' IDENTIFIED BY 'docver_pw';
GRANT ALL PRIVILEGES ON docver.* TO 'docver'@'localhost';
```

### 2단계: Spring Boot 띄우기

```powershell
cd C:\Users\user\Desktop\ldH\DocumentWorkflowAPI\Alpha
.\gradlew.bat bootRun
```

성공 확인 — 로그 마지막에 다음 라인이 나오면 정상:
```
Started DocumentVersionDemoApplication in N seconds
Tomcat started on port 8080
```

스키마/시드 자동 실행 확인:
- 로그 중간에 `schema.sql` 실행 메시지가 보여야 함
- 9개 테이블이 자동 생성됨

### 3단계: Postman 컬렉션 import

1. Postman 실행
2. Import → File 선택
3. `postman/DocVer-Demo-Scenario1.postman_collection.json` 선택

시연 8단계가 폴더로 나열됨.

---

## 회의 시연 (8단계, 약 5분)

### 시연 흐름 한 문장 요약

"alice가 문서 업로드/수정 후 bob에게 승인 요청 → bob이 알림 받고 승인 → alice가 최종 결과 알림 받음"

### Step 1 — 초기 버전 생성 (alice)

Postman "1. 초기 버전 생성" 실행 → 200 OK + `VersionInfo` 반환

이때 박사님께 보여드릴 포인트:
- 의사코드 `createInitialVersion`이 Java에서도 동일 동작
- 자동 트리거로 `version_created` 알림이 alice에게 자동 발송됨

### Step 2 — 문서 수정 (alice)

Postman "2. 문서 수정" 실행 → 200 OK + 새 `VersionInfo`

포인트:
- 새 버전 ID 생성 (timestamp+counter 패턴 — 03/18 ID 충돌 수정 반영)
- `version_updated` 알림 발송 (05/14 검토 #1로 잡은 이벤트 타입 오류 수정 반영)

### Step 3 — alice 본인 알림 확인

Postman "3. alice 본인 알림 확인" 실행 → 2개 알림 반환

포인트:
- alice는 본인이 작성자이자 마지막 수정자이므로 2개 모두 받음
- 의사코드 `getDefaultStakeholders`의 이벤트별 매트릭스(③ 단계) 동작 확인

### Step 4 — 승인 요청 (alice → bob)

Postman "4. 승인 요청" 실행 → 200 OK + `ruleId`, `status: UNDER_REVIEW`

포인트:
- `processApprovalWorkflow` REQUEST 분기 동작
- 파일이 under_review 상태로 자동 전이
- bob에게 명시 targets로 `approval_requested` 알림 발송

### Step 5 — bob 알림 확인

Postman "5. bob 알림 확인" 실행 → `approval_requested` 알림 1개

포인트:
- bob이 승인자로 알림 수신
- `unreadOnly=true` 파라미터로 안 읽은 것만 필터

### Step 6 — bob 안 읽은 알림 카운트

Postman "6. bob 안 읽은 알림 카운트" 실행 → `{ "unreadCount": 1 }`

포인트:
- UI 배지용 카운트 API
- 의사코드 `getUnreadCount` 1:1 매핑

### Step 7 — bob 승인 결정

Postman "7. bob 승인 결정" 실행 → `{ "success": true, "finalStatus": "APPROVED" }`

포인트:
- `processApprovalDecision` 동작 (THRESHOLD+1 합의 모드)
- 권한 확인 → 재결정 차단 검사 → 활동 기록 → 카운터 증가 → 합의 평가 → 상태 전이 → 알림 발송 → 규칙 종료
- 의사코드의 다단계 검증 흐름이 그대로 적용됨

### Step 8 — alice 최종 결과 알림 확인

Postman "8. alice 최종 결과 알림 확인" 실행 → `approval_completed` 알림 포함

포인트:
- **05/14 검토 #4에서 잡은 Outbox 우회 버그 수정 반영** — 요청자 알림이 `notifyStakeholders` 경로로 통일됨
- alice가 결정자(bob), 코멘트, 최종 상태를 모두 알 수 있음

---

## 회의 중 박사님 질문 예상 + 답변

### Q1. "이 코드 실제로 동작합니까?"
A. Postman 8단계가 위에 보여드린 그대로 200 OK로 응답합니다. 시연 직전 1분에 1~8단계 한 번 더 돌려보시면 안정성 보임.

### Q2. "의사코드와 동일하게 동작합니까?"
A. 의사코드의 7개 핵심 메서드를 1:1 매핑했습니다. README의 매핑 표 또는 코드 주석의 의사코드 라인 참조로 확인 가능.

### Q3. "테스트는?"
A. JUnit Testcontainers 기반 28개 통합 테스트 작성됨. Docker Desktop 환경에서 `./gradlew test -PrunTests=true`로 실행. 회의 환경(현재)은 Docker 미준비 상태라 비활성화. **테스트 코드 자체는 Scenario1IntegrationTest.java에 있어서 박사님께 코드로 보여드릴 수 있음.**

### Q4. "왜 시연에 다중 승인자/위임/보존 정책 등이 없습니까?"
A. 이번 알파는 보고서 7.2.1의 시나리오 1(라이프사이클 단일 흐름)만 범위로 정했습니다. 의사코드 측에는 다 있고 Java 전환은 7월~ 본격 단계에서 진행 예정입니다.

### Q5. "검토는 했습니까?"
A. 05/14 의사코드 전수 검토로 11건 버그/개선을 수정했고, 그 중 데모 흐름에 직접 영향 주는 3건(#1, #4, #5)을 Java 이식판에 반영했습니다. 상세는 `REVIEW_NOTES.md`.

---

## 문제 발생 시

### "Connection refused" 에러
→ MariaDB가 안 떠 있음. 사전 준비 1단계 다시 확인.

### "Table not found" 에러
→ schema.sql이 실행 안 됨. `application.yml`의 `spring.sql.init.mode: always` 확인. 또는 DB에 직접 schema.sql 내용 실행.

### Spring Boot가 시작 안 됨 (DB 연결 실패)
→ `application.yml`의 `spring.datasource.url/username/password`가 실제 DB와 일치하는지 확인.

### 시연 도중 데이터 꼬임
→ Adminer 또는 MySQL 콘솔에서 모든 테이블 TRUNCATE:
```sql
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE notifications;
TRUNCATE approval_activity;
TRUNCATE approval_rule_approvers;
TRUNCATE approval_rule_requesters;
TRUNCATE approval_rules;
TRUNCATE systemtag_object_mapping;
TRUNCATE activity;
TRUNCATE files_versions;
SET FOREIGN_KEY_CHECKS = 1;
```
status 태그는 seed.sql이 보존하므로 별도 처리 불필요.
