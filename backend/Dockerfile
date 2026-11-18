FROM maven:3.9.12-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml mvnw mvnw.cmd ./
COPY .mvn .mvn
RUN ./mvnw -DskipTests dependency:go-offline
COPY src src
RUN ./mvnw -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 1001 restos && mkdir -p /app/uploads && chown restos /app/uploads
COPY --from=build /workspace/target/restos-backend-*.jar app.jar
USER restos
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
