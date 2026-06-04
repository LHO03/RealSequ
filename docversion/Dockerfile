# ============================================================
# 멀티스테이지 Dockerfile
#   1단계(build): 컨테이너 안에서 Maven 빌드 → 호스트에 JDK/Maven 불필요
#   2단계(run)  : JRE만으로 실행 → 이미지 경량화
# 호스트 요구사항: Docker 하나뿐. `docker compose up --build`로 전부 동작.
# ============================================================

# ---- 1단계: 빌드 ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 의존성 레이어 캐싱: pom 먼저 복사 → 의존성 받아두면
# 소스만 바뀔 때 재다운로드를 피해 빌드가 빨라짐
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

# 소스 복사 후 패키징
# 테스트는 Testcontainers(Docker 필요)라 이미지 빌드 단계에서는 건너뛴다.
# (parity 테스트는 Docker가 있는 환경에서 `mvn test`로 별도 실행)
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ---- 2단계: 실행 ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
