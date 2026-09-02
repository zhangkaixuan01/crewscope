ARG NODE_IMAGE=node:24-alpine@sha256:d32cdf619f63fe0471182d08996dd516c6275bb5fd31ae06e55a570bd9e1ad43
ARG WEB_IMAGE=nginxinc/nginx-unprivileged:1.29-alpine@sha256:0c79d56aee561a1d81c63f00eee5fb5fe29279560cdc55e91425133104c7fbe6

FROM ${NODE_IMAGE} AS build
RUN corepack enable && corepack prepare pnpm@11.9.0 --activate
WORKDIR /workspace/crewscope-web
COPY crewscope-web/package.json crewscope-web/pnpm-lock.yaml crewscope-web/pnpm-workspace.yaml ./
RUN --mount=type=cache,target=/pnpm/store pnpm config set store-dir /pnpm/store \
    && pnpm install --frozen-lockfile
COPY crewscope-web ./
RUN pnpm build

FROM ${WEB_IMAGE} AS runtime
ARG CREWSCOPE_REVISION=unknown
ARG CREWSCOPE_VERSION=unknown

LABEL org.opencontainers.image.title="CrewScope Web" \
      org.opencontainers.image.version="${CREWSCOPE_VERSION}" \
      org.opencontainers.image.revision="${CREWSCOPE_REVISION}" \
      org.opencontainers.image.source="https://github.com/zhangkaixuan01/crewscope"

COPY deploy/team-beta/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build --chown=101:101 /workspace/crewscope-web/dist /usr/share/nginx/html
USER 101:101
EXPOSE 8080
