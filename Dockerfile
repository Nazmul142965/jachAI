FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/target/jachAI-0.0.1-SNAPSHOT.jar /app/app.jar
USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=prod
ENV JAVA_TOOL_OPTIONS="--add-opens java.base/java.math=ALL-UNNAMED -XX:MaxRAMPercentage=75.0"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
