FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /workspace

COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle

RUN chmod +x gradlew

COPY src ./src

# 테스트 실행 없이 실행 가능한 Spring Boot JAR를 생성함
RUN ./gradlew --no-daemon bootJar \
    && JAR_FILE="$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)" \
    && test -n "$JAR_FILE" \
    && cp "$JAR_FILE" /workspace/app.jar

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S spring \
    && adduser -S spring -G spring

WORKDIR /app

COPY --from=builder --chown=spring:spring /workspace/app.jar /app/app.jar

USER spring:spring

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=8 \
    CMD wget -q --spider http://127.0.0.1:8080/api/v1/categories || exit 1

STOPSIGNAL SIGTERM

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
