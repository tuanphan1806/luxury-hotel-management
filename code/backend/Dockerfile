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
RUN groupadd --system hotel \
    && useradd --system --gid hotel --home-dir /app --shell /usr/sbin/nologin hotel

WORKDIR /app
COPY --from=build --chown=hotel:hotel /workspace/backend.jar /app/backend.jar

# Stay times are hotel-local while provider/ledger timestamps remain Instant/UTC.
ENV TZ=Asia/Ho_Chi_Minh \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=25.0 -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Ho_Chi_Minh"

USER hotel
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/backend.jar"]
