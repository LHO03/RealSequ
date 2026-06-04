# docversion-core — 버전 생명주기 슬라이스

C++ 프로토타입(`DocumentVersionWorkflowAPI.cpp` / `Diffservice.h` / `Schema.sql`)을
**Spring Boot 3 + MyBatis(코어) + MariaDB**로 전환한 첫 수직 슬라이스.

대상 요구사항: RD-SRS-9.1(최초 버전), 9.2(수정 시 버전 생성), 9.3(변경 이력),
9.4(버전 diff), 9.5(특정 시점 버전 목록).

## 스택

- Java 21 / Spring Boot 3.3
- MyBatis (코어 SQL 직역) — MariaDB 고유 구문(`FOR UPDATE`, `INSERT IGNORE`, `JSON_SET`) 그대로 보존
- MariaDB 10.11 LTS / Flyway(스키마 마이그레이션)
- Testcontainers(실제 MariaDB로 통합 테스트 — H2 미사용)

## C++ → Spring 매핑 요약

| C++ | Java |
|---|---|
| `DocumentVersionWorkflowAPI`(버전 메서드) | `DocumentVersionService`(오케스트레이터) |
| `TransactionGuard`(RAII) | `VersionWriteService`의 `@Transactional` |
| `DatabaseConnection`(MySQL C API) | MyBatis 매퍼 + HikariCP |
| `FileStorage` | `StorageService` + `LocalFileStorage` |
| `DiffService` / `DocumentTextExtractor` | 동명 클래스 / 인터페이스 + Noop stub |
| `generateUUID` / `escapeJsonString`,`parseJson` | `UuidGenerator` / `VersionMetadata`(Jackson) |
| 후처리 `notifyStakeholders`,diff 캐시 | `@TransactionalEventListener(AFTER_COMMIT)` |

## 로컬 실행 (Docker만 필요)

호스트에 Docker만 있으면 됩니다(Java/Maven/IDE 불필요). 앱은 컨테이너 안에서 빌드됩니다.

```bash
docker compose up --build
```

- app(8080) + MariaDB(3306) + Adminer(8081)가 함께 기동
- Flyway가 V1 마이그레이션 자동 적용 (documents, files_versions, version_diffs, activity)
- 기동 확인: http://localhost:8080/actuator/health → {"status":"UP"}

상세한 데모 시나리오(파일 업로드 → 버전 생성/수정 → 목록 → Adminer로 DB 확인)는
**[DEMO.md](DEMO.md)** 참고.

> 호스트에 Java/Maven이 이미 있고 코드 반복 개발을 한다면 `mvn spring-boot:run`도 가능하지만,
> 데모/시연 목적이면 위 `docker compose` 한 줄이 가장 마찰이 적습니다.

## 테스트 (parity)

```bash
mvn test
# Docker 필요 (Testcontainers가 mariadb:10.11 컨테이너 기동)
# 검증: revision_no 단조 증가(FOR UPDATE), diff 캐시 적재(INSERT IGNORE), JSON_SET 메타데이터
```

## 다음 슬라이스

승인 워크플로우(9.7) → 알림+Outbox(9.9) → 보존 정책(9.10) → 보안(Spring Security).
단순 CRUD가 많이 불어나는 시점에 해당 애그리거트만 JPA 편입(하이브리드) 검토 —
테이블 소유권 단위로 경계를 긋고 같은 데이터를 양쪽으로 건드리지 않는다.
