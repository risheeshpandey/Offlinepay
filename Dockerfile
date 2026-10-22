FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
COPY observability/shared-resources ./observability/shared-resources
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app && mkdir -p /app/logs && chown app:app /app/logs
COPY --from=build /build/target/offlinepay-0.1.0-SNAPSHOT.jar /app/app.jar
USER app
EXPOSE 9030
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
