# Garuda devbox (GD-36): the sandbox where the Developer agent builds and tests client code.
# Toolchains: Java 21 (Maven, Gradle), Node 22 (npm, pnpm, yarn), Python 3.12 (pip). Runs as a non-root user,
# holds no credentials, and reaches the network only through the registry-only egress proxy (docker-compose.yml).
FROM node:22-bookworm-slim AS node

FROM eclipse-temurin:21-jdk-noble
ARG GRADLE_VERSION=8.10.2
RUN apt-get update \
 && apt-get install -y --no-install-recommends maven python3 python3-pip python3-venv git ca-certificates curl unzip make \
 && rm -rf /var/lib/apt/lists/* \
 && ln -sf /usr/bin/python3 /usr/local/bin/python \
 && curl -fsSL -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
 && unzip -q /tmp/gradle.zip -d /opt && ln -s /opt/gradle-${GRADLE_VERSION}/bin/gradle /usr/local/bin/gradle \
 && rm /tmp/gradle.zip
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
 && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
 && npm install -g --no-fund --no-audit pnpm@9.15.9 yarn@1.22.22 \
 && useradd --create-home --uid 10001 dev \
 && mkdir -p /workspaces && chown dev:dev /workspaces
# Maven ignores proxy environment variables: the global settings send it through the egress proxy.
RUN mkdir -p /etc/maven && printf '%s\n' '<settings><proxies>' \
    '<proxy><id>https</id><active>true</active><protocol>https</protocol><host>devbox-proxy</host><port>8888</port></proxy>' \
    '<proxy><id>http</id><active>true</active><protocol>http</protocol><host>devbox-proxy</host><port>8888</port></proxy>' \
    '</proxies></settings>' > /etc/maven/settings.xml
COPY server.py /opt/devbox/server.py
USER dev
ENV DEVBOX_ROOT=/workspaces \
    PIP_BREAK_SYSTEM_PACKAGES=1 \
    MAVEN_OPTS="-Xmx1g" \
    GRADLE_OPTS="-Dorg.gradle.daemon=false -Xmx1g"
EXPOSE 8090
HEALTHCHECK --interval=10s --timeout=5s --retries=10 CMD curl -fsS http://localhost:8090/health || exit 1
CMD ["python3", "/opt/devbox/server.py"]
