# Diagnostic only: never use this image for the production hotel backend.
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /probe
COPY diagnostics/cold-start/ColdStartProbe.java ./
RUN javac --release 17 --add-modules jdk.httpserver -d classes ColdStartProbe.java

FROM eclipse-temurin:17-jre-jammy AS runtime
RUN apt-get -o Acquire::Retries=0 update \
    && apt-get -o Acquire::Retries=0 install --yes --no-install-recommends apt libapt-pkg6.0 curl openssl libssl3 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system hotel \
    && useradd --system --gid hotel --home-dir /app --shell /usr/sbin/nologin hotel
WORKDIR /app
COPY --from=build --chown=hotel:hotel /probe/classes /app/classes
USER hotel
# Preserve the production Docker metadata for the initial control.
# The process still binds the service's PORT, defaulting to Render's 10000.
EXPOSE 8080
ENTRYPOINT ["java", "--add-modules", "jdk.httpserver", "-cp", "/app/classes", "ColdStartProbe"]
