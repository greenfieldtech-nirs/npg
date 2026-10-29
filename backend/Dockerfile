# syntax=docker/dockerfile:1
# Build context: the repository root (the protocol model in docs/protocol is part of the image).
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build
RUN apk add --no-cache maven
COPY backend/pom.xml .
RUN mvn dependency:go-offline -B -q
COPY backend/src ./src
RUN mvn clean package -DskipTests -B -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S npg && adduser -S npg -G npg && apk add --no-cache curl
COPY --from=builder /build/target/npg-backend-0.1.0-SNAPSHOT.jar app.jar
COPY docs/protocol /app/protocol
# No credentials are baked into the image: configuration comes from the environment.
ENV APP_ROLE=all \
    NPG_ENV=production \
    CATALOG_PATH=/app/protocol \
    HEALTH_PORT=9090 \
    LOG_LEVEL=INFO
RUN chown -R npg:npg /app
USER npg
EXPOSE 8080 8081 9090
# every role (api, inbound, worker, timer, dispatcher) serves health on HEALTH_PORT
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -fs http://localhost:${HEALTH_PORT}/health/ready > /dev/null || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
