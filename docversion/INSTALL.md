# 설치 및 실행 가이드

Windows 11 기준. 리눅스·macOS는 경로 표기만 바꾸면 그대로 적용된다.

목적에 따라 두 갈래로 나뉜다. **어느 쪽이 필요한지 먼저 정하고 시작하는 편이 빠르다.**

| | A. 실행만 | B. 개발·시험 |
|---|---|---|
| 필요한 것 | Docker Desktop | Docker Desktop + JDK 21 + Maven |
| 할 수 있는 것 | 서버 기동, 화면 시연, 탐지율 측정 | 위 전부 + 코드 수정, 단위·통합 시험 |
| 준비 시간 | 5분 | 20분 |
| 대상 | 시연을 보는 사람, 측정만 돌릴 사람 | 코드를 고칠 사람 |

앱은 컨테이너 안에서 빌드되므로, **A만 필요하면 호스트에 자바를 설치하지 않아도 된다.**

---

## A. 실행만 — Docker Desktop

### A-1. Docker Desktop 설치

<https://www.docker.com/products/docker-desktop/> 에서 받아 설치한 뒤 실행한다.
설치 후 확인:

```powershell
docker --version
docker compose version
```

> Docker Desktop이 실행 중이어야 `docker` 명령이 동작한다. 트레이 아이콘이
> "Engine running" 상태인지 확인할 것. WSL2 백엔드를 요구하는 경우 설치 과정에서
> 안내가 나오며, 재부팅이 한 번 필요할 수 있다.

### A-2. 기동

```powershell
cd C:\Users\<사용자>\Desktop\DocumentWorkflowAPI\docversion
docker compose up --build
```

최초 실행은 이미지 내려받기와 Maven 의존성 해석 때문에 **5~10분** 걸린다.
두 번째부터는 1분 이내다.

콘솔에 `Started DocversionApplication` 이 뜨면 준비된 것이다.

| 서비스 | 주소 | 용도 |
|---|---|---|
| 앱 | <http://localhost:8080> | API 서버 · 콘솔 화면 |
| Adminer | <http://localhost:8081> | DB 직접 조회 (Server `mariadb`, 계정 `nextcloud`/`nextcloud`) |
| MailHog | <http://localhost:8025> | 발송된 알림 메일 확인 |
| MariaDB | localhost:3306 | 직접 접속용 (선택) |

### A-3. 기동 확인

```powershell
curl.exe http://localhost:8080/actuator/health
```

`{"status":"UP"}` 이 나와야 한다.

Flyway가 V1~V18을 순서대로 적용해 테이블 19개를 만든다. 여기에는 DLP 규칙 5종
적재(V14)가 포함되며, **이 단계가 건너뛰어지면 모든 문서가 "민감하지 않음"으로
판정된다.** 규칙이 실려 있는지 확인하는 방법은 아래 A-5에 있다.

### A-4. 데모 계정

`demo` 프로필(기본값)에서만 시드된다. 운영 배포 시 `SPRING_PROFILES_ACTIVE=prod`로
기동하면 생성되지 않는다.

| 계정 | 비밀번호 | 권한 |
|---|---|---|
| alice | alice123 | 일반 |
| bob | bob123 | 일반 |
| admin | admin123 | 관리자 (보존 정책·DLP 규칙·알림 아웃박스) |

### A-5. 정지와 초기화

```powershell
docker compose down        # 정지. DB와 저장 파일은 유지된다
docker compose down -v     # 볼륨까지 삭제. 완전 초기화
```

측정을 다시 처음부터 하고 싶을 때는 `down -v`로 지우고 다시 올리는 편이 깔끔하다.
문서가 쌓인 채로 측정하면 이전 문서의 검사 작업이 워커 배치에 섞인다.

---

## B. 개발·시험 — JDK 21 + Maven

시험을 직접 돌리거나 코드를 고칠 때만 필요하다.

### B-1. JDK 21

```powershell
winget install EclipseAdoptium.Temurin.21.JDK
```

설치 후 **새 PowerShell 창을 열고** 확인한다(환경변수는 기존 창에 반영되지 않는다):

```powershell
java -version
echo $env:JAVA_HOME
```

`JAVA_HOME`이 비어 있으면 직접 지정한다. 경로는 설치된 실제 버전에 맞춘다:

```powershell
[Environment]::SetEnvironmentVariable(
  "JAVA_HOME",
  "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot",
  "User")
```

### B-2. Maven

**Apache Maven은 공식 winget 저장소에 없다.** `winget install Apache.Maven`은
"패키지를 찾을 수 없습니다"로 끝난다. zip 수동 설치가 정석이다.

아래는 한 줄씩 붙여 넣어도 되고 통째로 붙여 넣어도 된다. **경로를 전부 명시했으므로
중간에 창을 닫아도 다시 이어서 할 수 있다.**

```powershell
# 1) 내려받기
Invoke-WebRequest `
  -Uri "https://dlcdn.apache.org/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.zip" `
  -OutFile "$env:TEMP\maven.zip"

# 2) C:\Tools 아래에 푼다 (Program Files는 관리자 권한이 필요하므로 피한다)
New-Item -ItemType Directory -Path "C:\Tools" -Force | Out-Null
Expand-Archive -Path "$env:TEMP\maven.zip" -DestinationPath "C:\Tools" -Force

# 3) 실제로 풀렸는지 먼저 확인한다 (True가 나와야 한다)
Test-Path "C:\Tools\apache-maven-3.9.16\bin\mvn.cmd"

# 4) True일 때만 PATH에 추가한다
[Environment]::SetEnvironmentVariable(
  "Path",
  [Environment]::GetEnvironmentVariable("Path","User") + ";C:\Tools\apache-maven-3.9.16\bin",
  "User")
```

**새 PowerShell 창을 열고** 확인한다:

```powershell
mvn -v
```

> 3단계의 `Test-Path`를 건너뛰지 말 것. PATH만 등록해 두고 압축이 다른 곳에 풀려 있으면
> `mvn -v`가 아무것도 출력하지 않는데, 원인을 찾는 데 시간이 걸린다.

### B-3. 빌드와 시험

```powershell
cd C:\Users\<사용자>\Desktop\DocumentWorkflowAPI\docversion

mvn -B test-compile          # 컴파일만 (빠름, Docker 불필요)
mvn -B test                  # 전체 시험 (Docker 필요)
```

`DiffServiceLimitsTest`·`MimeDetectionTest`·`TikaExtractorLimitTest`와 dlp-core 시험 47건은 Docker 없이 돌아간다.
나머지 통합 시험은 Testcontainers로 **실제 MariaDB 컨테이너**를 띄운다(H2를 쓰지 않는다).
따라서 Docker Desktop이 실행 중이어야 하고, 전체 시험은 3~5분 걸린다.

특정 시험만 돌릴 때:

```powershell
mvn -B -pl docversion-app -am test "-Dtest=DiffServiceLimitsTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

> `-Dsurefire.failIfNoSpecifiedTests=false`가 **반드시 필요하다.** 이 프로젝트는
> 멀티모듈이라 지정한 시험이 없는 모듈(`dlp-core`)에서 빌드가 멈추고, 정작 돌려야 할
> `docversion-app`이 SKIPPED된다. 인자에 붙은 따옴표도 그대로 유지할 것 —
> PowerShell이 `=`를 만나면 인자를 쪼갠다.

---

## 확인 스크립트

서버가 뜬 상태에서 **다른 창**으로 실행한다.

| 스크립트 | 확인 대상 | 소요 |
|---|---|---|
| `docversion_test.ps1` | 9.x 전 범위 (절 0~14) | 2분 |
| `verify-9.10.ps1` | 보존 정책이 DLP 검사 이력과 함께 정리되는지 | 30초 |
| `verify-prereq.ps1` | 업로드 상한 · 413 응답 · 재검사 추출 상태 되돌림 | 1분 |
| `dlp-eval\measure-dlp.ps1` | 탐지율 측정 (문서 63개) | 5분 |

```powershell
powershell -ExecutionPolicy Bypass -File .\docversion_test.ps1
```

`-ExecutionPolicy Bypass`가 없으면 스크립트 실행이 정책에 막힌다.

---

## 화면으로 확인하기

<http://localhost:8080> 을 열면 콘솔 화면이 나온다. 시연 순서는 이렇다.

1. 상단에서 **alice / alice123** 으로 로그인
2. **1. 파일 업로드** — `dlp-eval\documents\A01_hr-record.txt` (인사기록카드) 를 올린다
3. **2. 문서 목록** 에서 방금 올린 문서를 클릭
4. **3. 선택한 문서** 로 내려가면
   - 버전 목록 (9.1 · 9.2 · 9.3)
   - **민감 데이터 검사** — 15초쯤 뒤 `검사 결과`를 누르면 판정과 탐지 항목이 나온다 (5.1 · 5.4)
5. 같은 문서에 `A07_severance-notice.txt` (퇴직정산 통지서) 를 수정본으로 올리면
   - **버전 비교**에 diff가 나오고 (9.4)
   - 변경분 검사(5.2)가 적재되어 `변경분 검사 · 5.2`로 조회된다

검사 결과 화면에서 **판정 불가(UNDETERMINED)** 는 "민감하지 않음"과 다른 색으로
표시된다. 안전하다는 뜻이 아니라 검사 자체가 이뤄지지 않았다는 뜻이며, 이 구분이
설계 전반의 원칙이다.

---

## 탐지율 측정

```powershell
cd C:\Users\<사용자>\Desktop\DocumentWorkflowAPI\docversion\dlp-eval
powershell -ExecutionPolicy Bypass -File .\measure-dlp.ps1
```

문서 63개를 올리고 검사가 끝날 때까지 기다린 뒤 혼동행렬과 지표를 낸다.
자세한 내용은 `dlp-eval\README.md` 에 있다.

측정 전에 규칙 적재 여부를 먼저 확인하며, 0건이면 지표를 내지 않고 중단한다.
직접 확인하려면:

```powershell
curl.exe -c a.txt -b a.txt -d "username=admin&password=admin123" http://localhost:8080/api/auth/login
curl.exe -b a.txt http://localhost:8080/api/dlp/rules
```

`"count":5` 가 나와야 정상이다.

---

## 자주 막히는 곳

| 증상 | 원인과 조치 |
|---|---|
| `docker` 명령이 없다고 나옴 | Docker Desktop이 실행 중이 아니다. 트레이에서 시작하고 "Engine running"을 확인 |
| `mvn test`에서 `Could not find a valid Docker environment` | 같은 원인이다. 통합 시험은 실제 MariaDB 컨테이너를 띄우므로 Docker Desktop이 켜져 있어야 한다. 첫 시험이 실패하면 Testcontainers가 `Will not retry`로 나머지를 연쇄 포기하므로, 오류 개수와 무관하게 원인은 하나다. Docker를 켠 뒤 `docker ps`로 확인하고 `mvn -B test -rf :docversion-app`으로 이어서 실행 |
| 첫 통합 시험이 한참 멈춘 것처럼 보임 | Testcontainers가 `mariadb:10.11` 이미지를 처음 내려받는 중이다. 3~5분 걸리며 두 번째부터는 빠르다 |
| `mvn -v`가 아무것도 출력하지 않음 | 압축이 PATH가 가리키는 곳에 풀리지 않았다. B-2의 `Test-Path`부터 다시 |
| 환경변수를 등록했는데 반영이 안 됨 | 기존 PowerShell 창에는 반영되지 않는다. 새 창을 열 것 |
| `-Dtest=X`인데 시험이 안 돌아감 | `-Dsurefire.failIfNoSpecifiedTests=false` 누락. B-3 참고 |
| 스크립트가 실행되지 않음 | `-ExecutionPolicy Bypass` 누락 |
| 8080 포트가 이미 사용 중 | 다른 프로세스가 점유 중이다. `netstat -ano \| findstr :8080` 으로 PID 확인 후 종료 |
| 업로드가 413으로 거부됨 | 기본 상한 20MB. `DOCVERSION_MAX_FILE_SIZE` 환경변수로 조정 (`compose.yaml`의 `app` 서비스) |
| 검사가 계속 PENDING | 워커 주기가 15초다. 그보다 오래 걸리면 앱 로그에서 `[DLP 워커]` 를 확인 |
| 모든 문서가 "민감하지 않음" | 규칙이 0건일 가능성이 높다. 위 규칙 확인 명령을 실행. 앱 기동 로그에도 경고가 남는다 |
| 한글이 깨짐 | 스크립트 파일이 UTF-8 BOM으로 저장되어 있어야 한다. 편집 시 인코딩을 유지할 것 |
| 스크립트가 서버 응답을 해석하지 못함 | PowerShell은 `curl.exe`의 표준출력을 **콘솔 코드페이지**(한국어 Windows는 CP949)로 해석한다. 서버가 UTF-8 한글을 보내면 깨지면서 따옴표까지 잃어 JSON 구조가 망가진다. `measure-dlp.ps1`은 응답을 파일로 받아 UTF-8로 직접 읽어 이 경로를 피한다. 새 스크립트를 쓸 때도 `curl.exe \| ConvertFrom-Json` 대신 `-o <파일>` 로 받을 것 |
| 측정 스크립트가 파일을 못 찾음 | 시험 문서 파일명은 전부 ASCII다(제목은 정답표의 `title` 열에 있다). 파일명을 한글로 바꾸면 PowerShell이 curl.exe에 넘길 때 코드페이지 문제로 실패할 수 있다 |

---

## 구성 요약

```
docversion/
  pom.xml                  부모 (모듈 2개)
  dlp-core/                탐지 엔진. 의존성 없는 순수 자바 — 스프링도 DB도 모른다
  docversion-app/          스프링 애플리케이션 (9.x 전 범위 + 5.x 배선)
  dlp-eval/                탐지율 측정 세트
  compose.yaml             app + mariadb + adminer + mailhog
  Dockerfile               컨테이너 안에서 빌드 (호스트에 자바 불필요)
  docversion_test.ps1      9.x 통합 확인
  verify-9.10.ps1          보존 정책 확인
  verify-prereq.ps1        업로드 상한·재검사 확인
```

모듈을 둘로 나눈 이유는 의존 방향을 컴파일 단계에서 강제하기 위해서다.
`dlp-core`는 스프링과 DB를 알지 못하므로 그 방향으로 실수로 의존할 수 없고,
나중에 별도 서비스로 떼어낼 여지가 남는다.
