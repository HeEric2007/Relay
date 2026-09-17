FROM gradle:8.10-jdk21 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN gradle --no-daemon bootJar

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar
EXPOSE 8080

# Sized for a 512MB shared instance: the JVM defaults to a quarter of the
# container, and SerialGC beats G1 when there is well under a full CPU.
# Set as an env var so it survives a platform overriding the command below.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"

# Exec form, no shell wrapper: SIGTERM has to reach the JVM for graceful shutdown to work.
# CMD rather than ENTRYPOINT so a platform process command replaces it cleanly.
CMD ["java", "-jar", "app.jar"]
