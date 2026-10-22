FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
COPY services services
COPY observability/shared-resources observability/shared-resources
RUN mvn -B -f services/pom.xml -DskipTests package
ARG MODULE=core
RUN if [ "$MODULE" = core ]; then cp target/offlinepay-0.1.0-SNAPSHOT.jar /app.jar; \
    else cp services/$MODULE/target/offlinepay-$MODULE-0.1.0-SNAPSHOT.jar /app.jar; fi

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app && mkdir logs && chown app:app logs
COPY --from=build /app.jar app.jar
USER app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
