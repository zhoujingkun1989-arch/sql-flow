# syntax=docker/dockerfile:1.7
# ==============================================================================
# Clean-Room SQLFlow 8.2.2.15 Unified Single-Container Build
# Build from repository root:
#   docker build -f deploy/Dockerfile -t sqlflow-cleanroom:8.2.2.15 .
# ==============================================================================

# ------------------------------------------------------------------------------
# Stage 1: Node 22 + pnpm Web SPA & Widget SDK Builder
# ------------------------------------------------------------------------------
FROM node:22-bookworm-slim AS web-builder

WORKDIR /workspace

RUN corepack enable && corepack prepare pnpm@10.34.3 --activate

COPY package.json pnpm-workspace.yaml pnpm-lock.yaml tsconfig.base.json ./
COPY ui-core/package.json ui-core/package.json
COPY frontend-ui/package.json frontend-ui/package.json
COPY widget-sdk/package.json widget-sdk/package.json
COPY deploy/e2e/package.json deploy/e2e/package.json

RUN pnpm install --frozen-lockfile

COPY ui-core/ ui-core/
COPY frontend-ui/ frontend-ui/
COPY widget-sdk/ widget-sdk/

RUN pnpm --filter @sqlflow/ui-core build \
    && pnpm --filter @sqlflow/frontend-ui build \
    && pnpm --filter @sqlflow/widget-sdk build

# ------------------------------------------------------------------------------
# Stage 2: Maven JDK 17+ Backend Builder
# ------------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-17 AS java-builder

WORKDIR /workspace

COPY pom.xml ./
COPY contracts/pom.xml contracts/pom.xml
COPY lineage-engine/pom.xml lineage-engine/pom.xml
COPY graph-layout/pom.xml graph-layout/pom.xml
COPY metadata-connectors/pom.xml metadata-connectors/pom.xml
COPY backend-service/pom.xml backend-service/pom.xml

RUN mvn -B -q -DskipTests dependency:go-offline || true

COPY contracts/ contracts/
COPY lineage-engine/ lineage-engine/
COPY graph-layout/ graph-layout/
COPY metadata-connectors/ metadata-connectors/
COPY backend-service/ backend-service/
COPY --from=web-builder /workspace/frontend-ui/dist/ frontend-ui/dist/
COPY --from=web-builder /workspace/widget-sdk/dist/ widget-sdk/dist/

RUN mvn -B -DskipTests package

# ------------------------------------------------------------------------------
# Stage 3: Eclipse Temurin 17 JRE Unified Runtime (:8165)
# ------------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy AS runtime

LABEL org.opencontainers.image.title="gudu-sqlflow" \
      org.opencontainers.image.version="8.2.2.15" \
      org.opencontainers.image.description="Clean-room SQLFlow 8.2.2.15 unified on-premise SQL lineage platform" \
      org.opencontainers.image.ports="8165"

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=en_US.UTF-8 \
    LANGUAGE=en_US:en \
    LC_ALL=en_US.UTF-8 \
    SQLFLOW_HOME=/sqlflow \
    AI_ENABLED=false \
    AI_MODEL=claude-sonnet-4-20250514

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        fontconfig \
        fonts-dejavu-core \
        locales \
        openssl \
        procps \
        tzdata \
    && locale-gen en_US.UTF-8 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /sqlflow

RUN mkdir -p \
    /sqlflow/bin \
    /sqlflow/conf \
    /sqlflow/conf.defaults \
    /sqlflow/data/job/task \
    /sqlflow/data/share \
    /sqlflow/data/permalink \
    /sqlflow/data/schema \
    /sqlflow/data/session/sqlflow \
    /sqlflow/data/user/settings \
    /sqlflow/lib \
    /sqlflow/log/sqlflow_logs \
    /sqlflow/tmp/cache/sqlflow \
    /sqlflow/widget \
    /sqlflow/static

COPY --from=java-builder /workspace/backend-service/target/backend-service-8.2.2.15.jar /sqlflow/lib/sqlflow-server.jar
COPY --from=web-builder /workspace/frontend-ui/dist/ /sqlflow/static/
COPY --from=web-builder /workspace/widget-sdk/dist/ /sqlflow/widget/

COPY deploy/conf/ /sqlflow/conf/
COPY deploy/conf/ /sqlflow/conf.defaults/
COPY deploy/bin/ /sqlflow/bin/
COPY deploy/docker-entrypoint.sh /sqlflow/docker-entrypoint.sh

RUN chmod +x /sqlflow/docker-entrypoint.sh /sqlflow/bin/*.sh \
    && ln -sf /sqlflow/lib/sqlflow-server.jar /sqlflow/lib/backend-service-8.2.2.15.jar \
    && mkdir -p /opt/sqlflow-image \
    && cp -a /sqlflow/bin /sqlflow/lib /sqlflow/static /sqlflow/widget /sqlflow/conf.defaults /sqlflow/docker-entrypoint.sh /opt/sqlflow-image/

EXPOSE 8165

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -sf http://localhost:8165/ || exit 1

ENTRYPOINT ["/opt/sqlflow-image/docker-entrypoint.sh"]
