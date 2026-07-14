FROM gradle:8.14-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
COPY --from=build /workspace/build/libs/*.jar /app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
