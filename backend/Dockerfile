# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Install Maven for the build stage
RUN apk add --no-cache maven

# Copy only pom.xml first to leverage Docker layer caching for dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build the application JAR
COPY src ./src
RUN mvn clean package -DskipTests -B

# ------------------------------------------------------------------------------
# Runtime image
# ------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Create non-root user/group and install curl so the HEALTHCHECK can probe
# the readiness endpoint. wget is not present in the JRE Alpine image by default.
RUN addgroup -S npg && adduser -S npg -G npg && apk add --no-cache curl

# Default environment variables (overridden by Compose or runtime)
ENV APP_ROLE=all
ENV DB_HOST=db
ENV DB_PORT=3306
ENV DB_NAME=npg
ENV DB_USER=npg
ENV DB_PASSWORD=npg
ENV DB_POOL_MAX_SIZE=10
ENV MONGO_URI=mongodb://mongo:27017
ENV MONGO_DB=npg
ENV LOG_LEVEL=INFO

# Copy the built JAR from the builder stage
COPY --from=builder /build/target/npg-backend-0.1.0-SNAPSHOT.jar app.jar

# Run as non-root
RUN chown -R npg:npg /app
USER npg

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8080/health/ready || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
