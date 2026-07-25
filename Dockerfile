# syntax=docker/dockerfile:1

# ============================================================================
# Stage 1: Build — Gradle로 bootJar 생성
# ============================================================================
FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /workspace

# 의존성 레이어 캐싱: 빌드 스크립트/wrapper만 먼저 복사해 소스 변경 시에도
# 의존성 다운로드 레이어가 재사용되도록 한다.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null || true

# 소스 복사 후 실행 가능한 jar만 빌드 (테스트는 CI 단계에서 별도 실행한다고 가정하고 스킵)
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# ============================================================================
# Stage 2: Runtime — 빌드 도구 없이 JRE만 포함한 최소 이미지
# ============================================================================
FROM eclipse-temurin:25-jre-alpine AS runtime
WORKDIR /app

# non-root 사용자로 실행 (컨테이너 보안 기본 원칙)
RUN addgroup -S spring && adduser -S spring -G spring

COPY --from=builder /workspace/build/libs/app.jar ./app.jar
RUN chown spring:spring ./app.jar

USER spring

ENV JAVA_OPTS="" \
    SPRING_PROFILES_ACTIVE=prod

EXPOSE 8080

# Actuator readiness probe — DB/Redis 연결 상태까지 확인한 뒤 UP을 반환한다.
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD wget -q --spider http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
