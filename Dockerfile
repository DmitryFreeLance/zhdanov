FROM maven:3.9.11-eclipse-temurin-21

WORKDIR /app

COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
COPY .env.example ./.
COPY README.md ./README.md

RUN mvn -q -DskipTests package
RUN mvn -q -DskipTests exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install --with-deps chromium"

ENV APP_DATABASE_PATH=/app/data/wb-max-bot.db
ENV APP_WILDBERRIES_STORAGE_STATE_PATH=/app/data/wb-storage-state.json

VOLUME ["/app/data"]
EXPOSE 8080

CMD ["java", "-jar", "/app/target/wb-max-bot-1.0.0.jar"]
