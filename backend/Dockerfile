FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon
COPY src src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends procps curl unzip && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/build/libs/*.jar app.jar

# Extract JARs for Spark subprocess (separate JVM with ANTLR 4.9.3)
RUN mkdir -p /app/spark-cp && \
    cd /app/spark-cp && \
    unzip -q /app/app.jar "BOOT-INF/lib/*" "BOOT-INF/classes/*" && \
    rm -f BOOT-INF/lib/antlr4-runtime-*.jar && \
    curl -sL -o BOOT-INF/lib/antlr4-runtime-4.9.3.jar \
      https://repo1.maven.org/maven2/org/antlr/antlr4-runtime/4.9.3/antlr4-runtime-4.9.3.jar

# OpenShift typically runs containers with an arbitrary non-root UID.
RUN chgrp -R 0 /app && chmod -R g=u /app

EXPOSE 8080
ENTRYPOINT ["java", "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED", "--add-opens=java.base/java.nio=ALL-UNNAMED", "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED", "-jar", "app.jar"]
