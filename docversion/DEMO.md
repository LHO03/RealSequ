# 데모 실행 가이드 (Docker만 필요)

호스트에 **Docker만** 있으면 됩니다. Java·Maven·IDE 설치 불필요.
Nextcloud 없이, REST 업로드로 파일을 넣어 버전 관리 동작을 확인합니다.

## 1. 기동

프로젝트 폴더에서:

```bash
docker compose up --build
```

- 최초 1회는 컨테이너 안에서 Maven 빌드가 돌아 몇 분 걸립니다(이후엔 캐시되어 빠름).
- MariaDB가 준비되면 app이 기동하고, Flyway가 4개 테이블을 자동 생성합니다.
- 기동 확인: <http://localhost:8080/actuator/health> → `{"status":"UP"}`

## 2. 시나리오: 문서 1개의 버전을 쌓아본다

테스트용 파일 2개를 만듭니다.

```bash
printf 'line1\nline2\nline3\n'            > spec.txt
printf 'line1\nline2-edited\nline3\nline4\n' > spec_v2.txt
```

### (1) 최초 버전 생성 — RD-SRS-9.1

```bash
curl -F userId=alice -F path=/projects/spec.txt -F file=@spec.txt \
  http://localhost:8080/api/documents
```

응답의 `fileId`를 복사해 둡니다(이후 단계에 사용). `revisionNo`는 1입니다.

### (2) 수정 → 새 버전 자동 생성 — RD-SRS-9.2

```bash
curl -F userId=alice -F file=@spec_v2.txt \
  http://localhost:8080/api/documents/<fileId>/versions
```

`revisionNo`가 2로 올라갑니다(FOR UPDATE로 단조 증가 보장).

### (3) 버전 목록 조회 — RD-SRS-9.5

```bash
curl "http://localhost:8080/api/documents/<fileId>/versions?userId=alice"
```

두 버전이 최신순으로 반환됩니다.

## 3. DB로 결과 눈으로 확인 (Adminer)

<http://localhost:8081> 접속 → System: **MySQL/MariaDB**, Server: `mariadb`,
Username/Password: `nextcloud` / `nextcloud`, Database: `nextcloud`.

확인 포인트(보고서 스크린샷용으로도 유용):

- `files_versions` — 같은 `file_id`에 `revision_no` 1, 2가 쌓임
- `version_diffs` — (v1→v2) diff 캐시 1행. `diff_method=myers`, `added_lines=2`, `deleted_lines=1`
- `activity` — 변경 이력. `subjectparams`에 reason/versionId가 JSON으로 기록
- `files_versions.metadata` — `JSON_SET`으로 들어간 `{"author":...,"reason":...}`

## 4. 정지 / 초기화

```bash
docker compose down       # 컨테이너만 정지 (데이터 유지)
docker compose down -v    # DB·저장소 볼륨까지 삭제 (완전 초기화)
```

## 보고서 메모

- 본 데모는 **Nextcloud 미연동 독립 구성**입니다. 파일 입출력은 `StorageService`
  인터페이스(현재 `LocalFileStorage` 구현)가 담당하며, 이 지점이 향후 Nextcloud/오브젝트
  스토리지가 꽂힐 seam입니다. 즉 "Nextcloud 연동은 필수가 아니라 교체 가능한 통합 지점"임을
  데모가 실증합니다.
- parity 테스트(`mvn test`, Docker 필요)는 실제 MariaDB(Testcontainers)에서 MariaDB 고유
  SQL(`FOR UPDATE`, `INSERT IGNORE`, `JSON_SET`)을 검증합니다.
