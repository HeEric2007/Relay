FROM gradle:8.10-jdk21 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN gradle --no-daemon bootJar

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar
EXPOSE 8080
# Exec form, no shell wrapper: SIGTERM has to reach the JVM for graceful shutdown to work.
# CMD rather than ENTRYPOINT so a platform process command replaces it cleanly.
CMD ["java", "-jar", "app.jar"]
