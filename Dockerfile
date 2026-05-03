# ── Stage 1: build fat-jar ──────────────────────────────────────────────────
FROM gradle:8.7-jdk21 AS builder

WORKDIR /build
# Copy build scripts first to leverage layer cache
COPY --chown=gradle:gradle build.gradle settings.gradle ./
COPY --chown=gradle:gradle src ./src

RUN gradle shadowJar --no-daemon --quiet

# ── Stage 2: runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy

# krb5-user provides kinit/klist and the Kerberos client libraries
# required by the JVM's Krb5LoginModule at runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends krb5-user \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /build/build/libs/*-all.jar app.jar

EXPOSE 9116

CMD ["java", "-jar", "app.jar"]
