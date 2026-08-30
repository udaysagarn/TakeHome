# One container, one file-backed H2 database: the whole control plane runs on a laptop.
FROM maven:3.9-eclipse-temurin-21 AS build
# Force a specific mirror (e.g. an internal Nexus). Empty means "use Maven Central".
ARG MAVEN_MIRROR_URL=
# Shared IPs get HTTP 429 from Maven Central often enough that an unattended demo
# build needs somewhere else to go; this read-only mirror is Google's copy of Central.
ARG MAVEN_FALLBACK_MIRROR=https://maven-central.storage-download.googleapis.com/maven2
WORKDIR /build
COPY use-mirror.sh .
RUN if [ -n "$MAVEN_MIRROR_URL" ]; then ./use-mirror.sh "$MAVEN_MIRROR_URL"; fi
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package \
    || { echo "menD: Maven Central build failed, retrying through $MAVEN_FALLBACK_MIRROR"; \
         ./use-mirror.sh "$MAVEN_FALLBACK_MIRROR" && mvn -B -DskipTests package; }

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 mend && mkdir -p /app/data && chown -R mend /app
COPY --from=build /build/target/*.jar /app/mend.jar
USER mend
VOLUME ["/app/data"]
EXPOSE 8080
ENV MEND_DB_URL="jdbc:h2:file:/app/data/mend;AUTO_SERVER=TRUE"
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s \
  CMD ["sh", "-c", "wget -qO- http://localhost:8080/actuator/health | grep -q UP"]
ENTRYPOINT ["java", "-jar", "/app/mend.jar"]
