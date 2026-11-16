ARG JDK_IMAGE=container-registry.oracle.com/java/openjdk:27.0.1.0.3@sha256:ec8568d488454d0087e88a38b818abc46098ab8701843e9449db3468a641a326
ARG BUILD_JDK_IMAGE=${JDK_IMAGE}
FROM ${BUILD_JDK_IMAGE} AS builder

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

FROM ${JDK_IMAGE} AS runtime

RUN groupadd --system spring \
    && useradd --system --gid spring --no-create-home --shell /sbin/nologin spring

WORKDIR /app

COPY --from=builder --chown=spring:spring /workspace/app.jar /app/app.jar

USER spring:spring

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=8 \
    CMD curl --fail --silent --output /dev/null http://127.0.0.1:8080/api/v1/categories || exit 1

STOPSIGNAL SIGTERM

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
