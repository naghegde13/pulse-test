#!/bin/bash
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
unset SPRING_PROFILES_ACTIVE
unset SPRING_CLOUD_GCP_SQL_INSTANCE_CONNECTION_NAME
cd /Users/aameradam/projects/dev/PULSE/backend && ./gradlew bootRun
