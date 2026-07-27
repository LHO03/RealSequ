# DocumentWorkflowAPI

Nextcloud 기반 문서 관리 시스템의 **문서 형상관리 모듈** (RD-SRS-9.x).

C++ 의사코드로 업무 규칙을 확정한 뒤, 이를 Java(Spring Boot)로 전환하는 방식으로 진행한다.
이 저장소에는 **양쪽이 함께** 있으며 역할이 다르다.

---

## 구성

```
DocumentWorkflowAPI/
├── DocumentVersionWorkflowAPI.cpp    C++ 의사코드 — 업무 규칙 정본
├── Diffservice.h                     diff 계산 명세
├── Schema.sql                        C++ 단계 스키마 (19개 테이블)
├── Review_notes.md                   05/14 의사코드 전수 검토 기록
└── docversion/                       Java 구현체 ← 현재 개발 대상
```

| 항목 | 역할 |
|---|---|
| **C++ 의사코드** | 업무 규칙의 **정본**. 순수 로직 결함은 여기서 먼저 고친다 |
| **`docversion/`** | 실행 가능한 구현체. 트랜잭션·이벤트·인프라 계층을 담당 |
| **`Review_notes.md`** | 의사코드 검토 이력. 미해결 항목이 남아 있어 참고 가치가 있다 |

---

## 개발 원칙

**업무 규칙은 C++ 단계에서 확정한다.** Java로 옮기는 것은 트랜잭션 관리, 이벤트 처리 같은
인프라 계층이다. 순수한 업무 규칙에 결함이 발견되면 C++ 단계에서 먼저 수정한다.

양쪽에서 각각 고치면 어느 쪽이 기준인지 알 수 없게 되기 때문이다.

---

## 실행

구현체는 `docversion/` 안에 있다. 호스트에 Docker만 있으면 된다.

```bash
cd docversion
docker compose up --build
```

- 앱 <http://localhost:8080> — API 서버 및 콘솔 화면
- Adminer <http://localhost:8081> — DB 조회
- MailHog <http://localhost:8025> — 알림 메일 확인

**상세한 실행법·API 목록·설계 요점·테스트 방법은 [docversion/README.md](docversion/README.md)를 참고한다.**

---

## 진행 상황

| 범위 | 상태 |
|---|---|
| RD-SRS-9.1 ~ 9.10 | 구현 완료 |
| 스키마 (Flyway) | V13까지 적용 |
| 인증·인가 | Spring Security, 기본 거부 방식 |
| 검증 | 통합 76건 + JUnit 25건 전부 통과 |

> RD-SRS-9.8은 명세서상 존재하지 않는다 (9.7 다음이 9.9).

잔여 과제는 `docversion/README.md`의 「알려진 제약 · 잔여 과제」 절에 정리되어 있다.

---

## 이력

- **05/14** — C++ 의사코드 전수 검토. 11건 수정, 5건은 정책 확인 대기 (`Review_notes.md`)
- **07월** — Java 전환 및 P1 결함 정리 (MIME 전달, 예외 분류, diff 상태 기계, 정렬, 알림 중복 키)