# syntax=docker/dockerfile:1
# Backend image. Build context: this directory (Ddareungi-rider/), e.g. `docker build -t ddareungi-rider-backend .`

# ---- 1) Build the Spring Boot jar ----
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace

# Gradle wrapper and build scripts first, so dependency resolution is cached until they change.
COPY gradlew settings.gradle build.gradle ./
COPY gradle/ gradle/
COPY backend/build.gradle backend/
RUN --mount=type=cache,target=/root/.gradle \
    sed -i 's/\r$//' gradlew \
    && sh gradlew :backend:dependencies --configuration runtimeClasspath --no-daemon -q > /dev/null

COPY backend/src/ backend/src/
RUN --mount=type=cache,target=/root/.gradle \
    sh gradlew :backend:bootJar --no-daemon -q \
    && find backend/build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' -exec cp {} application.jar \; \
    && test -f application.jar

# ---- 2) Split the jar into layers (Spring Boot jarmode tools, available since Boot 3.3) ----
FROM eclipse-temurin:21-jre AS extractor
WORKDIR /extract
COPY --from=builder /workspace/application.jar application.jar
# Layers that are empty for this project (e.g. no SNAPSHOT dependencies) are created so every COPY below succeeds.
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted \
    && mkdir -p extracted/dependencies extracted/spring-boot-loader extracted/snapshot-dependencies extracted/application

# ---- 3) Runtime ----
FROM eclipse-temurin:21-jre
# curl is used by the compose healthcheck.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin app
WORKDIR /app
# Least frequently changing layers first.
COPY --from=extractor /extract/extracted/dependencies/ ./
COPY --from=extractor /extract/extracted/spring-boot-loader/ ./
COPY --from=extractor /extract/extracted/snapshot-dependencies/ ./
COPY --from=extractor /extract/extracted/application/ ./
USER 10001:10001
ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# 8080: API. The actuator management port (MANAGEMENT_SERVER_PORT, default 8090) is internal and not exposed here.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
