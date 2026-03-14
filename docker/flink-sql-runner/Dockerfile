# Stage 1: Build sql-runner JAR + resolve all connector deps via Maven
FROM maven:3.9-eclipse-temurin-11 AS builder
WORKDIR /build

# Resolve connector JARs (includes Confluent repo transitive deps) and S3 plugin
COPY connector-pom.xml .
RUN mvn -f connector-pom.xml dependency:copy-dependencies \
      -DoutputDirectory=/connector-deps \
      -DincludeScope=runtime \
      -q && \
    mkdir -p /s3-plugin && \
    cp /connector-deps/flink-s3-fs-hadoop-*.jar /s3-plugin/ && \
    test -n "$(ls -A /s3-plugin 2>/dev/null)" || (echo "S3 plugin JAR not found in connector-deps" && ls -la /connector-deps/ && exit 1)

# Build the sql-runner JAR
COPY pom.xml .
COPY src ./src
RUN mvn -f pom.xml package -q -DskipTests

# Stage 2: Custom Flink image
FROM flink:1.18-scala_2.12

# Copy all connector JARs into Flink's lib directory
COPY --from=builder /connector-deps/*.jar /opt/flink/lib/

# S3 filesystem plugin (required for s3:// checkpoint/savepoint paths; must be in plugins/ subdir)
# See https://nightlies.apache.org/flink/flink-docs-stable/docs/deployment/filesystems/plugins/
RUN mkdir -p /opt/flink/plugins/s3-fs-hadoop
COPY --from=builder /s3-plugin/ /opt/flink/plugins/s3-fs-hadoop/
RUN test -n "$(ls -A /opt/flink/plugins/s3-fs-hadoop 2>/dev/null)" || (echo "ERROR: S3 plugin JAR missing in plugins/s3-fs-hadoop" && exit 1)

# Install the sql-runner JAR where FlinkDeploymentBuilder expects it
RUN mkdir -p /opt/flink/usrlib
COPY --from=builder /build/target/sql-runner.jar /opt/flink/usrlib/sql-runner.jar
