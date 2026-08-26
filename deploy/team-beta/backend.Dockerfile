ARG MAVEN_IMAGE=maven:3.9.11-eclipse-temurin-17-alpine@sha256:b6ecb971326f147d214081527518bd32bceb55dba3bd5460d9a1d9f4b04464fd
ARG RUNTIME_IMAGE=eclipse-temurin:17-jre-alpine@sha256:27cc0849148c0fd32ee8e95988917becf9bc96a3182a24f99d9763aa8e90f8cb

FROM ${MAVEN_IMAGE} AS build
ARG MAVEN_OPTS
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY crewscope-domain/pom.xml crewscope-domain/pom.xml
COPY crewscope-application/pom.xml crewscope-application/pom.xml
COPY crewscope-agentscope/pom.xml crewscope-agentscope/pom.xml
COPY crewscope-integration/pom.xml crewscope-integration/pom.xml
COPY crewscope-infrastructure/pom.xml crewscope-infrastructure/pom.xml
COPY crewscope-server/pom.xml crewscope-server/pom.xml

COPY crewscope-domain/src crewscope-domain/src
COPY crewscope-application/src crewscope-application/src
COPY crewscope-agentscope/src crewscope-agentscope/src
COPY crewscope-integration/src crewscope-integration/src
COPY crewscope-infrastructure/src crewscope-infrastructure/src
COPY crewscope-server/src crewscope-server/src
RUN --mount=type=cache,target=/root/.m2 mvn --batch-mode --no-transfer-progress \
    -pl crewscope-server -am -Dmaven.test.skip=true package

FROM ${RUNTIME_IMAGE} AS runtime
ARG CREWSCOPE_UID=10001
ARG CREWSCOPE_GID=10001

# The Worker invokes only the platform-owned Git and Docker CLI adapters. Package installation stays
# in the immutable image build; the runtime root filesystem is read-only.
RUN apk add --no-cache docker-cli git tini \
    && addgroup -S -g "${CREWSCOPE_GID}" crewscope \
    && adduser -S -D -H -u "${CREWSCOPE_UID}" -G crewscope crewscope \
    && install -d -o crewscope -g crewscope \
      /app /var/crewscope/artifacts /var/crewscope/github-mirrors \
      /var/crewscope/github-credentials /var/crewscope/git-home \
      /var/crewscope/repositories /var/crewscope/worktrees \
      /var/crewscope/worktree-locks /var/crewscope/runtime

WORKDIR /app
COPY --from=build --chown=10001:10001 \
    /workspace/crewscope-server/target/crewscope-server-0.1.0-SNAPSHOT.jar /app/crewscope.jar

USER 10001:10001
EXPOSE 8080 8081
ENTRYPOINT ["/sbin/tini", "-s", "--", "java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-Djava.io.tmpdir=/tmp", "-jar", "/app/crewscope.jar"]
