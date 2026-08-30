# One container, one file-backed H2 database: the whole control plane runs on a laptop.
FROM maven:3.9-eclipse-temurin-21 AS build
# Point at an internal mirror when Maven Central is unreachable or rate-limited.
ARG MAVEN_MIRROR_URL=
WORKDIR /build
RUN if [ -n "$MAVEN_MIRROR_URL" ]; then \
      mkdir -p /root/.m2 && printf '%s\n' \
        '<settings><mirrors><mirror><id>mirror</id><mirrorOf>central</mirrorOf>' \
        "<url>$MAVEN_MIRROR_URL</url></mirror></mirrors></settings>" > /root/.m2/settings.xml; \
    fi
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

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
