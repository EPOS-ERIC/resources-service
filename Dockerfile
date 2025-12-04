FROM amazoncorretto:21-alpine

LABEL org.opencontainers.image.source="https://github.com/your-org/your-repo" \
      org.opencontainers.image.description="Your app description" \
      org.opencontainers.image.licenses="MIT"

# Upgrade all packages to fix vulnerabilities + add minimal dependencies
RUN apk upgrade --no-cache \
    && apk add --no-cache ca-certificates tzdata curl \
    && addgroup -g 1001 -S appgroup \
    && adduser -u 1001 -S appuser -G appgroup

WORKDIR /app
COPY --chown=1001:1001 target/*.jar app.jar

# Explicit numeric UID for better detection
USER 1001:1001

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]