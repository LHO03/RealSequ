# ============================================================
# 멀티스테이지 Dockerfile
#   1단계(build): 컨테이너 안에서 Maven 빌드 → 호스트에 JDK/Maven 불필요
#   2단계(run)  : JRE만으로 실행 → 이미지 경량화
# 호스트 요구사항: Docker 하나뿐. `docker compose up --build`로 전부 동작.
#
# 08/17: 멀티모듈 전환 반영 (RD-SRS-5.x 결합 준비)
#   부모(docversion) 아래 dlp-core / docversion-app 두 모듈 구조로 바뀌면서
#   pom 복사와 산출물 경로가 달라졌다.
# ============================================================

# ---- 1단계: 빌드 ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 의존성 레이어 캐싱: pom을 먼저 복사해 의존성을 받아두면
# 소스만 바뀔 때 재다운로드를 피해 빌드가 빨라진다.
# 멀티모듈이므로 부모와 두 모듈의 pom을 모두 복사해야 리액터가 구성된다.
COPY pom.xml .
COPY dlp-core/pom.xml dlp-core/
COPY docversion-app/pom.xml docversion-app/
# -pl: 대상 모듈, -am: 그 모듈이 의존하는 형제 모듈(dlp-core)도 함께 처리
RUN mvn -q -B -pl docversion-app -am dependency:go-offline

# 소스 복사 후 패키징
# 테스트는 Testcontainers(Docker 필요)라 이미지 빌드 단계에서는 건너뛴다.
# (parity 테스트는 Docker가 있는 환경에서 `mvn test`로 별도 실행)
COPY dlp-core/src ./dlp-core/src
COPY docversion-app/src ./docversion-app/src

# 07/22: 빌드 안전장치 — 최신 수정(null 방어)이 소스에 있는지 확인하고, 없으면 빌드를 즉시 실패시킨다.
#   옛 소스로 빌드되는 사고(다른 폴더/브랜치 복사본)를 컴파일 단계에서 바로 드러내기 위함.
#   08/17: 멀티모듈 전환으로 경로가 docversion-app/ 아래로 이동.
RUN grep -q "roles != null" docversion-app/src/main/java/com/docversion/service/DocumentAccessPolicy.java \
    && grep -q "subs != null" docversion-app/src/main/java/com/docversion/service/DocumentAccessPolicy.java \
    || (echo "BUILD ABORTED: DocumentAccessPolicy.java is an OLD version (null-guard missing)" && exit 1)

ARG CACHEBUST=1
RUN echo "cache-bust=${CACHEBUST}" && mvn -q -B clean package -DskipTests

# 08/18: fat jar가 실제로 만들어졌는지 빌드 단계에서 확인한다.
#   spring-boot:repackage가 돌지 않으면 Main-Class 없는 일반 jar가 나오고,
#   컨테이너가 뜬 뒤에야 "no main manifest attribute"로 죽어 원인 파악이 늦어진다.
#   BOOT-INF/ 항목 존재가 repackage 수행의 증거다. jar 명령은 JDK에 포함되어 있다.
RUN jar tf docversion-app/target/docversion-app-0.1.0-alpha.jar | grep -q "^BOOT-INF/" \
    || (echo "BUILD ABORTED: fat jar가 아닙니다. spring-boot:repackage 실행 여부를 확인하십시오." && exit 1)

# 08/19: 메서드 파라미터 이름이 바이트코드에 남았는지 확인한다.
#   -parameters 플래그가 빠지면 Spring MVC가 @RequestParam 이름을 읽지 못해
#   업로드를 포함한 모든 파라미터 바인딩이 400으로 실패한다. 컴파일과 기동은
#   정상이라 화면에서 눌러보기 전까지 드러나지 않으므로 빌드 단계에서 잡는다.
RUN javap -v -cp docversion-app/target/classes com.docversion.web.DocumentVersionController \
      | grep -q "MethodParameters" \
    || (echo "BUILD ABORTED: 파라미터 이름이 없습니다. maven-compiler-plugin의 -parameters 설정을 확인하십시오." && exit 1)

# ---- 2단계: 실행 ----
FROM eclipse-temurin:21-jre
WORKDIR /app
# 08/17: 산출물 경로가 target/ → docversion-app/target/ 으로 이동.
#   dlp-core는 라이브러리 jar이며 docversion-app의 fat jar 안에 포함되므로 별도 복사하지 않는다.
# 08/18: 와일드카드 대신 파일명을 명시한다.
#   target에는 repackage 산출물(fat jar)과 원본(*.jar.original)이 함께 남는데,
#   *.jar 패턴이 어느 쪽을 집을지 보장되지 않아 실행 불가 jar가 복사될 수 있다.
COPY --from=build /build/docversion-app/target/docversion-app-0.1.0-alpha.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
