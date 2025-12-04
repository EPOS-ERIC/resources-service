FROM amazoncorretto:21-alpine-jdk AS builder

RUN apk add --no-cache binutils

WORKDIR /app
COPY target/*.jar app.jar

RUN $JAVA_HOME/bin/jlink \
    --verbose \
    --add-modules java.base,java.management,java.naming,java.net.http,java.security.jgss,java.security.sasl,java.sql,jdk.httpserver,jdk.unsupported,java.logging \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=zip-6 \
    --output /custom-jre

FROM alpine:3.20

LABEL org.opencontainers.image.source="https://github.com/your-org/your-repo" \
      org.opencontainers.image.description="Your app description" \
      org.opencontainers.image.licenses="MIT"

# Upgrade all packages to fix vulnerabilities + add minimal dependencies
RUN apk upgrade --no-cache \
    && apk add --no-cache ca-certificates tzdata curl \
    && addgroup -g 1001 -S appgroup \
    && adduser -u 1001 -S appuser -G appgroup

COPY --from=builder /custom-jre /opt/java

WORKDIR /app
COPY --from=builder --chown=1001:1001 /app/app.jar app.jar

ENV JAVA_HOME=/opt/java \
    PATH="/opt/java/bin:$PATH"

# Explicit numeric UID for better detection
USER 1001:1001

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]