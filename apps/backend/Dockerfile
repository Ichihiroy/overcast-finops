# ── Build stage ───────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Cache dependencies separately from source for fast rebuilds
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B package -DskipTests && \
    java -Djarmode=layertools -jar target/*.jar extract --destination extracted

# ── Runtime stage ─────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Numeric uid so Kubernetes runAsNonRoot can verify non-root without a chart
# runAsUser (a named USER fails that admission check). 10001 = dedicated app user.
RUN addgroup -S app && adduser -S -G app -u 10001 app
USER 10001

COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
