FROM mcr.microsoft.com/playwright/java:v1.53.0-noble AS build

RUN apt-get update \
    && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build

COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src

RUN mvn -q -DskipTests package

FROM mcr.microsoft.com/playwright/java:v1.53.0-noble

WORKDIR /app

COPY --from=build /build/target/wb-max-bot-1.0.0.jar /app/wb-max-bot.jar
COPY docker/certs /app/certs
COPY docker/entrypoint.sh /app/entrypoint.sh

RUN chmod +x /app/entrypoint.sh

ENV APP_DATABASE_PATH=/app/data/wb-max-bot.db
ENV APP_WILDBERRIES_STORAGE_STATE_PATH=/app/data/wb-storage-state.json
ENV APP_WILDBERRIES_BROWSER_EXECUTABLE_PATH=
ENV APP_WILDBERRIES_HEADLESS=false
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
ENV APP_TRUSTED_CERTS_DIR=/app/certs
ENV APP_IMPORT_TRUSTED_CERTS=true
ENV APP_JAVA_CACERTS_PASSWORD=changeit

VOLUME ["/app/data"]
EXPOSE 8080

ENTRYPOINT ["/app/entrypoint.sh"]
CMD ["xvfb-run", "-a", "java", "-jar", "/app/wb-max-bot.jar"]
