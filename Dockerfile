# ── Build stage ────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /build

# Download dependencies first (cached layer)
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B dependency:go-offline -q 2>/dev/null || true

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests package

# ── Runtime stage ──────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
LABEL org.opencontainers.image.title="DevLens MCP Server"
LABEL org.opencontainers.image.description="Statically-extracted, provenance-tagged repo facts via MCP (STDIO transport)"

# Non-root user
RUN addgroup -S devlens && adduser -S -G devlens devlens
USER devlens

WORKDIR /app
COPY --from=build /build/target/devlens.jar devlens.jar

# devlens-data is mounted at runtime by the MCP client
ENV DEVLENS_DATA_DIR=/data

# stdout IS the JSON-RPC channel — nothing must print to it
ENTRYPOINT ["java", "-jar", "/app/devlens.jar"]

