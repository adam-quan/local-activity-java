# One image for every role: api, limiter, worker, runner.
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /src
COPY settings.gradle build.gradle ./
# Resolve dependencies in their own layer so source edits rebuild quickly.
RUN gradle dependencies --configuration runtimeClasspath --no-daemon -q > /dev/null
COPY src ./src
RUN gradle installDist --no-daemon -x test

FROM eclipse-temurin:21-jre
COPY --from=build /src/build/install/lab /opt/lab
# Quiet SDK logging; small heaps keep eight virtual workers plus the runner on a laptop.
ENV JAVA_OPTS="-XX:+UseZGC -XX:MaxRAMPercentage=60 -Dorg.slf4j.simpleLogger.defaultLogLevel=warn"
ENTRYPOINT ["/opt/lab/bin/lab"]
