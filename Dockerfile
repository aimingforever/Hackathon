# Runtime image for AskOnce. Build the jar first: ./mvnw -DskipTests package
FROM eclipse-temurin:25-jre

WORKDIR /app
RUN useradd --system --uid 10001 askonce
COPY target/goodfish-0.0.1-SNAPSHOT.jar app.jar
USER 10001

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
