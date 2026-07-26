# syntax=docker/dockerfile:1
# Multi-stage build: compile with Maven, run on a slim JRE. Built and run locally only.
# The --mount=type=cache for ~/.m2 shares the Maven repo with the module builds.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
# Verified on Saturday, 2026-07-25 — pipeline flow check (README is paths-ignored on its own).
ENTRYPOINT ["java", "-jar", "app.jar"]
