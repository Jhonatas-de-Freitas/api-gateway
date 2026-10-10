# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY src/main src/main
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -q package -DskipTests
RUN cp target/api-gateway-*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre-alpine
LABEL org.opencontainers.image.source="https://github.com/Jhonatas-de-Freitas/api-gateway"
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER app
EXPOSE 8080
# Readiness leaves Redis out on purpose: without it the gateway still routes, only without rate limit.
HEALTHCHECK --interval=5s --timeout=3s --start-period=30s --retries=12 \
    CMD wget -q -O /dev/null "http://localhost:${SERVER_PORT:-8080}/actuator/health/readiness" || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
