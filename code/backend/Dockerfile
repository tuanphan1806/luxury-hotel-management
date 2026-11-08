# syntax=docker/dockerfile:1

FROM maven:3.9.12-eclipse-temurin-17 AS build
WORKDIR /workspace
ARG MAVEN_HTTP_RETRIES=5

# Cache dependencies separately from source changes.
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -Pprod \
    -Dmaven.wagon.http.retryHandler.count=${MAVEN_HTTP_RETRIES} \
    dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -Pprod -DskipTests \
    -Dmaven.wagon.http.retryHandler.count=${MAVEN_HTTP_RETRIES} \
    package \
    && cp target/backend-*.jar /workspace/backend.jar

FROM eclipse-temurin:17-jre-jammy AS runtime
# Refresh APT/OpenSSL even when the base image predates Ubuntu security patches.
# No retries while bootstrapping the APT fix (CVE-2026-109518).
RUN apt-get -o Acquire::Retries=0 update \
    && apt-get -o Acquire::Retries=0 install --yes --no-install-recommends apt libapt-pkg6.0 curl openssl libssl3 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system hotel \
    && useradd --system --gid hotel --home-dir /app --shell /usr/sbin/nologin hotel

WORKDIR /app
COPY --from=build --chown=hotel:hotel /workspace/backend.jar /app/backend.jar

# Stay times are hotel-local while provider/ledger timestamps remain Instant/UTC.
ENV TZ=Asia/Ho_Chi_Minh \
    MALLOC_ARENA_MAX=2 \
    JAVA_TOOL_OPTIONS="-Xms64m -Xmx384m -Xss512k -XX:+UseSerialGC -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Ho_Chi_Minh"

USER hotel
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/backend.jar"]
