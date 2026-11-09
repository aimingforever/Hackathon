# Runtime image for AskOnce. Build the jar first: ./mvnw -DskipTests package
FROM eclipse-temurin:25-jre

ARG OTEL_AGENT_VERSION=2.33.1

WORKDIR /app
RUN useradd --system --uid 10001 askonce
# OpenTelemetry Java agent: auto-instruments Spring MVC, the Elasticsearch client, logs and JVM metrics.
ADD --chmod=644 https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar
COPY target/goodfish-0.0.1-SNAPSHOT.jar app.jar
USER 10001

EXPOSE 8080
ENTRYPOINT ["java", "-javaagent:/app/opentelemetry-javaagent.jar", "-jar", "/app/app.jar"]
