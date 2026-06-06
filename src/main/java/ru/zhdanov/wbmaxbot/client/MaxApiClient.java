package ru.zhdanov.wbmaxbot.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import ru.zhdanov.wbmaxbot.config.AppProperties;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MaxApiClient {

    private static final Logger log = LoggerFactory.getLogger(MaxApiClient.class);

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public MaxApiClient(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    public boolean isEnabled() {
        return properties.getMax().isEnabled() && hasText(properties.getMax().getToken());
    }

    public void sendMessage(long chatId, String text) {
        if (!isEnabled()) {
            log.info("MAX disabled, skipping message to chat {}", chatId);
            return;
        }

        try {
            String query = "chat_id=" + URLEncoder.encode(String.valueOf(chatId), StandardCharsets.UTF_8);
            URI uri = URI.create(properties.getMax().getBaseUrl() + "/messages?" + query);
            String body = objectMapper.writeValueAsString(Map.of(
                    "text", text,
                    "notify", true
            ));
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Authorization", properties.getMax().getToken())
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("MAX sendMessage failed: HTTP " + response.statusCode() + " " + response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to send MAX message", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to send MAX message", e);
        }
    }

    public void registerWebhookIfNeeded() {
        if (!isEnabled()) {
            return;
        }
        if (!properties.getMax().isAutoRegisterWebhook()) {
            return;
        }
        if (!hasText(properties.getMax().getPublicWebhookUrl())) {
            log.warn("MAX auto webhook registration is enabled but app.max.public-webhook-url is empty");
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("url", properties.getMax().getPublicWebhookUrl());
        payload.put("update_types", List.of("bot_started", "bot_stopped", "bot_removed", "dialog_removed", "message_created"));
        if (hasText(properties.getMax().getWebhookSecret())) {
            payload.put("secret", properties.getMax().getWebhookSecret());
        }

        try {
            URI uri = URI.create(properties.getMax().getBaseUrl() + "/subscriptions");
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Authorization", properties.getMax().getToken())
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("MAX webhook registration failed: HTTP {} {}", response.statusCode(), response.body());
                return;
            }
            JsonNode json = objectMapper.readTree(response.body());
            log.info("MAX webhook registration result: {}", json);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to register MAX webhook", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to register MAX webhook", e);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
