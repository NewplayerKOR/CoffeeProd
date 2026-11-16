ARG JDK_IMAGE=container-registry.oracle.com/java/openjdk:27.0.1.0.3@sha256:ec8568d488454d0087e88a38b818abc46098ab8701843e9449db3468a641a326
ARG BUILD_JDK_IMAGE=${JDK_IMAGE}
FROM ${BUILD_JDK_IMAGE} AS builder
WORKDIR /workspace
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
COPY src ./src
RUN chmod +x gradlew && ./gradlew --no-daemon perfBootJar performanceClasses
ARG PERF_SOURCE_HASH
ARG PERF_MEASUREMENT_HASH
LABEL coffeeprod.perf.source=${PERF_SOURCE_HASH}
ENV PERF_COMPILED_SOURCE_HASH=${PERF_SOURCE_HASH}

# Contains JDK/Gradle cache for diagnostic checks; checks never overlap HTTP timing runs.
FROM builder AS harness
COPY scripts/performance ./scripts/performance
COPY scripts/seed ./scripts/seed
ENTRYPOINT ["sh", "scripts/performance/checks.sh"]

FROM ${JDK_IMAGE} AS runtime
ARG PERF_SOURCE_HASH
ARG PERF_MEASUREMENT_HASH
LABEL coffeeprod.perf.source=${PERF_SOURCE_HASH}
ENV PERF_COMPILED_SOURCE_HASH=${PERF_SOURCE_HASH}
RUN groupadd --system perf \
    && useradd --system --gid perf --no-create-home --shell /sbin/nologin perf
WORKDIR /app
COPY --from=builder /workspace/build/libs/*-perf.jar /app/perf.jar
USER perf
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=90s --retries=30 CMD curl --fail --silent --output /dev/null http://127.0.0.1:8080/api/v1/categories || exit 1
ENTRYPOINT ["java","-jar","/app/perf.jar"]
