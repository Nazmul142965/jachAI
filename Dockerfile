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
# JAVA_TOOL_OPTIONS is split on whitespace, so every option has to be a single
# token. "--add-opens java.base/java.math=ALL-UNNAMED" was two, and the JVM
# refused to start with "Unrecognized option: --add-opens".
#
# It is gone rather than rewritten with an "=". It was only ever needed because a
# derived sum() query made Spring Data treat BigDecimal as a document type and
# reflect into its private fields. PaymentOrderRepositoryImpl now does that sum
# as a hand-written aggregation, so Spring never introspects BigDecimal and the
# module does not need opening. Verified by starting the application with no
# add-opens at all: every repository bean was created.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
