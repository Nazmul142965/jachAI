FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .

# Maven Central answers 429 to a busy shared build network, and a single refused
# download fails the whole build after eight minutes of work. Retrying the
# individual request costs nothing when it succeeds and saves the build when it
# does not.
ENV MVN_FLAGS="-B -q -Dmaven.wagon.http.retryHandler.count=5 -Dmaven.wagon.httpconnectionManager.ttlSeconds=120"

# -Dmaven.test.skip=true, not -DskipTests. -DskipTests still COMPILES the tests,
# so the image was resolving Mockito, Byte Buddy and the rest of the test tree
# only to throw the classes away — and those were among the artifacts that got
# refused. The container build already does not run tests; test the same
# revision before publishing an image, as SYSTEM.md says.
COPY docker/maven-settings.xml /build/settings.xml
# Resolve through the mirror, retry the whole step a few times, and make the
# last attempt go straight to Central in case the mirror is the one that is down.
RUN n=0; until mvn $MVN_FLAGS -s /build/settings.xml -Dmaven.test.skip=true dependency:go-offline; do       n=$((n+1)); [ $n -ge 3 ] && exec mvn $MVN_FLAGS -Dmaven.test.skip=true dependency:go-offline; sleep 20; done
COPY src ./src
RUN n=0; until mvn $MVN_FLAGS -s /build/settings.xml -Dmaven.test.skip=true package; do       n=$((n+1)); [ $n -ge 3 ] && exec mvn $MVN_FLAGS -Dmaven.test.skip=true package; sleep 20; done

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-eng && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/target/jachAI-0.0.1-SNAPSHOT.jar /app/app.jar
USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=prod
ENV RESUME_OCR_ENABLED=true
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
# C1-only compilation and the serial collector suit a host with a fraction of one CPU: less time compiling and
# fewer background threads, so the first requests after a start or a quiet spell are not starved of CPU.
# Every option is a single token, as the note above requires.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:TieredStopAtLevel=1 -XX:+UseSerialGC"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
