FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /build

COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src

RUN mvn -q -DskipTests package

FROM mcr.microsoft.com/playwright/java:v1.53.0-noble

WORKDIR /app

COPY --from=build /build/target/wb-max-bot-1.0.0.jar /app/wb-max-bot.jar

ENV APP_DATABASE_PATH=/app/data/wb-max-bot.db
ENV APP_WILDBERRIES_STORAGE_STATE_PATH=/app/data/wb-storage-state.json

VOLUME ["/app/data"]
EXPOSE 8080

CMD ["java", "-jar", "/app/wb-max-bot.jar"]
