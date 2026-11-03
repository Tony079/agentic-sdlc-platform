# Backend image: one artifact, two roles. Default command serves the API; with
# "--spring.profiles.active=migrate" it applies migrations and exits.
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# Tests run in CI (they need a PostgreSQL instance); the image build only packages.
RUN mvn -B -q -DskipTests package && cp target/sdlc-platform-*.jar /build/app.jar

FROM eclipse-temurin:21-jre-noble
RUN groupadd --system sdlc && useradd --system --gid sdlc --home-dir /app --shell /usr/sbin/nologin sdlc
WORKDIR /app
COPY --from=build --chown=root:root /build/app.jar /app/app.jar
USER sdlc
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
