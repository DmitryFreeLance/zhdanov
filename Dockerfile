FROM maven:3.9.11-eclipse-temurin-21-noble AS build

WORKDIR /build

COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src

RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-noble

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl gnupg fonts-liberation xdg-utils \
    && install -m 0755 -d /etc/apt/keyrings \
    && curl -fsSL https://dl.google.com/linux/linux_signing_key.pub | gpg --dearmor -o /etc/apt/keyrings/google-chrome.gpg \
    && chmod a+r /etc/apt/keyrings/google-chrome.gpg \
    && echo "deb [arch=amd64 signed-by=/etc/apt/keyrings/google-chrome.gpg] http://dl.google.com/linux/chrome/deb/ stable main" > /etc/apt/sources.list.d/google-chrome.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends google-chrome-stable \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /build/target/wb-max-bot-1.0.0.jar /app/wb-max-bot.jar

ENV APP_DATABASE_PATH=/app/data/wb-max-bot.db
ENV APP_WILDBERRIES_STORAGE_STATE_PATH=/app/data/wb-storage-state.json
ENV APP_WILDBERRIES_BROWSER_EXECUTABLE_PATH=/usr/bin/google-chrome
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

VOLUME ["/app/data"]
EXPOSE 8080

CMD ["java", "-jar", "/app/wb-max-bot.jar"]
