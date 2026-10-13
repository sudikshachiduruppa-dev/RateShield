# ----------------------------------------------------------------------------
# Multi-stage Dockerfile for RateShield Spring Boot Backend on Render
# ----------------------------------------------------------------------------

# Stage 1: Build executable JAR using Maven wrapper and Java 21
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copy Maven wrapper and project descriptor
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Normalize line endings and ensure execution permission on Linux
RUN sed -i 's/\r$//' ./mvnw && chmod +x ./mvnw

# Copy source files (excludes local secrets via .dockerignore)
COPY src/ ./src/

# Build production JAR (skips tests during image build)
RUN ./mvnw clean package -DskipTests -B

# ----------------------------------------------------------------------------
# Stage 2: Lightweight Production Runtime
# ----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Run as unprivileged system user for container security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Copy generated Spring Boot executable JAR
COPY --from=builder /app/target/rateshield-0.0.1-SNAPSHOT.jar app.jar

# Render dynamically sets PORT (defaults to 8080)
ENV PORT=8080
EXPOSE ${PORT}

# Run Spring Boot binding directly to Render's assigned PORT
ENTRYPOINT ["sh", "-c", "java -Dserver.port=${PORT} -jar app.jar"]
