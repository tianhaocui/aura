# syntax=docker/dockerfile:1.7
FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml,required=false \
    mvn --batch-mode --no-transfer-progress dependency:go-offline
COPY src ./src
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml,required=false \
    mvn --batch-mode --no-transfer-progress verify

FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system --gid 10001 aura \
    && useradd --system --uid 10001 --gid aura --home-dir /app --shell /usr/sbin/nologin aura
WORKDIR /app
COPY --from=build --chown=aura:aura /workspace/target/${artifactId}-${version}.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
