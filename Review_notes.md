# 05/14 의사코드 검토 — 발견·수정 내역

회의 보고용 요약. Java 전환 직전 의사코드 전수 검토 결과.

## 검토 범위

- 1순위 (시나리오 1 직결): 7개 메서드
- 2순위 (1순위에서 호출되는 헬퍼): 8개 메서드
- 3순위 (데모 범위 밖): 10개 메서드 + 보존 정책 영역

검토 시간: 약 2시간. 메서드별 정밀 점검.

## 수정 사항 (11건)

### 1순위 (시나리오 1 직접 영향)

| # | 메서드 | 유형 | 내용 |
|---|---|---|---|
| 1 | `onDocumentModified` | **의미 오류** | `dispatchEvent("version_created")` → `"version_updated"`. 외부 워크플로우 엔진이 두 이벤트(생성/업데이트)를 구분 처리 가능 |
| 2 | `onDocumentModified` | 주석 명확화 | `policyManager` 스텁 호출 의도 명시 (호환성 유지용 무동작) |
| 3 | `processApprovalWorkflow` | **PK 충돌 방어** | 중복 승인자 dedup (`unordered_set`). 같은 ID 두 번 입력 시 두 번째 INSERT 예외로 부분 INSERT 상태 발생하던 것 |
| 4 | `processApprovalDecision` | **Outbox 우회** | 요청자 알림을 `sendNotification` 직접 호출 → `notifyStakeholders`로 통일. A-X에서 도입한 Outbox/dedup 인프라가 이 경로만 우회하던 것 |
| 5 | `getDefaultStakeholders` | **빈 결과 버그** | `approval_completed`/`cancelled`/`reverted` 이벤트가 OPEN rule만 조회해서 broadcast 대상이 비어버리던 것. CLOSED rule도 포함하는 `_Any` 변형 람다 2개 추가 |

### 2순위

| # | 메서드 | 유형 | 내용 |
|---|---|---|---|
| 6 | `processApprovalDecision` | **위임+SEQUENTIAL 버그** | 위임받은 사용자가 SEQUENTIAL에서 결정 시 본인 sequence_order가 없어 영구 거부되던 것. 위임자의 sequence_order로 검사하도록 수정 |

### 3순위

| # | 메서드 | 유형 | 내용 |
|---|---|---|---|
| 7 | `revokeRole` | **보안 (lockout 방지)** | 마지막 ADMIN 회수 차단. 시스템에 ADMIN 1명일 때 본인 권한 회수 시 영구 잠금되던 것 |
| 8 | `subscribeToFile` | PK 충돌 방어 | 중복 채널 dedup (#3과 동일 패턴) |
| 9 | `deleteVersion` | **보안 (권한 누락)** | 누구나 다른 사람 버전 삭제 가능하던 것. 파일 소유자/관리자만 허용 |
| 10 | `deleteVersion` | 자동 트리거 누락 | `version_deleted` 알림 발송 추가 (보고서 ③ 매트릭스 의도 부합) |
| 11 | `prepareVersionComparison` | 데이터 누락 | 캐시 hit 시 `diff_method` enum 복원 누락 → `stringToDiffMethod` 헬퍼 추가 |

## 검토 노트 (수정 안 한 항목 — 박사님 확인 필요)

| 영역 | 사항 | 박사님 확인 사항 |
|---|---|---|
| `evaluateConsensus` | `totalApprovers=0` 시 UNANIMOUS/SEQUENTIAL이 즉시 APPROVED | 비정상 데이터 방어 필요 여부 |
| `applyVersionRetentionPolicy` | `maxVersions=1`인데 최근 2개 무조건 보존 | "최소 2개 보장" vs "정책 우선" 정책 결정 |
| `applyToAllFiles` | FOLDER LIKE 와일드카드 이스케이프 없음 | Java 전환 시 통합 해결 영역 (보고서 6장 (1) 유형) |
| `cancelApprovalRequest` | 위임받은 사용자 CANCEL 권한 미부여 | "결정은 위임, CANCEL은 별도" 정책 의도 확인 |
| `notifyStakeholders` | 동기 `flushOutboxImmediate`로 응답 지연 | Java 전환 시 `@Async`로 해결 |

## 데이터 영향

- 코드: cpp 3,375 → 3,497 줄 (+122, 대부분 안전장치와 주석)
- 수정 마크: `// 05/14` 16개 위치
- DB 스키마: 변경 없음

## 영향이 큰 수정 (만약 검토 없이 Java로 옮겼다면)

- **#1 이벤트 타입 오류**: Java 전환 후 `VersionUpdatedEvent` 발행 로직 작성 시 의도와 어긋난 이벤트가 발행되어 리스너 매핑 혼선
- **#4 Outbox 우회**: Spring Retry 도입 후 한 경로만 누락되어 일관성 손실
- **#5 빈 broadcast**: 시연에서 "alice가 결과 알림 못 받음" 시나리오가 발생할 수 있었음

수정 #1, #4, #5는 데모 동작 자체에 영향. 검토 안 했으면 시연 직전에 발견했을 가능성 큼.