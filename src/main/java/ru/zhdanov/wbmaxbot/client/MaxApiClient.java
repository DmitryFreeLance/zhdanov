package ru.zhdanov.wbmaxbot.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import ru.zhdanov.wbmaxbot.config.AppProperties;
import ru.zhdanov.wbmaxbot.model.MaxOutgoingMessage;

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
import java.util.StringJoiner;

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
        sendMessage(chatId, new MaxOutgoingMessage(text));
    }

    public void sendMessage(long chatId, MaxOutgoingMessage message) {
        if (!isEnabled()) {
            log.info("MAX disabled, skipping message to chat {}", chatId);
            return;
        }

        try {
            String query = "chat_id=" + URLEncoder.encode(String.valueOf(chatId), StandardCharsets.UTF_8);
            URI uri = URI.create(properties.getMax().getBaseUrl() + "/messages?" + query);
            Map<String, Object> bodyPayload = new LinkedHashMap<>();
            bodyPayload.put("text", message.text());
            bodyPayload.put("notify", message.notifyUsers());
            if (message.attachments() != null && !message.attachments().isEmpty()) {
                bodyPayload.put("attachments", message.attachments());
            }
            String body = objectMapper.writeValueAsString(bodyPayload);
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

    public void answerCallback(String callbackId, String notification, MaxOutgoingMessage message) {
        if (!isEnabled() || callbackId == null || callbackId.isBlank()) {
            return;
        }

        try {
            URI uri = URI.create(properties.getMax().getBaseUrl() + "/answers?callback_id="
                    + URLEncoder.encode(callbackId, StandardCharsets.UTF_8));
            Map<String, Object> bodyPayload = new LinkedHashMap<>();
            if (notification != null && !notification.isBlank()) {
                bodyPayload.put("notification", notification);
            }
            if (message != null) {
                Map<String, Object> messagePayload = new LinkedHashMap<>();
                messagePayload.put("text", message.text());
                messagePayload.put("notify", message.notifyUsers());
                if (message.attachments() != null && !message.attachments().isEmpty()) {
                    messagePayload.put("attachments", message.attachments());
                }
                bodyPayload.put("message", messagePayload);
            }

            String body = objectMapper.writeValueAsString(bodyPayload);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Authorization", properties.getMax().getToken())
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("MAX answerCallback failed: HTTP " + response.statusCode() + " " + response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to answer MAX callback", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to answer MAX callback", e);
        }
    }

    public void registerWebhookIfNeeded() {
        if (!isEnabled()) {
            return;
        }
        if (properties.getMax().isLongPollingEnabled()) {
            log.info("Skipping MAX webhook registration because long polling is enabled");
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
        payload.put("update_types", List.of("bot_started", "bot_stopped", "bot_removed", "dialog_removed", "message_created", "message_callback"));
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

    public MaxUpdatesResponse getUpdates(Long marker) {
        if (!isEnabled()) {
            return new MaxUpdatesResponse(List.of(), marker);
        }

        try {
            StringJoiner query = new StringJoiner("&");
            query.add("limit=" + properties.getMax().getLongPollingLimit());
            query.add("timeout=" + properties.getMax().getLongPollingTimeout().toSeconds());
            query.add("types=bot_started,message_created,message_callback,bot_stopped,bot_removed,dialog_removed");
            if (marker != null) {
                query.add("marker=" + marker);
            }

            URI uri = URI.create(properties.getMax().getBaseUrl() + "/updates?" + query);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Authorization", properties.getMax().getToken())
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("MAX getUpdates failed: HTTP " + response.statusCode() + " " + response.body());
            }

            JsonNode json = objectMapper.readTree(response.body());
            List<JsonNode> updates = objectMapper.convertValue(json.path("updates"), objectMapper.getTypeFactory().constructCollectionType(List.class, JsonNode.class));
            Long nextMarker = json.path("marker").isNull() || json.path("marker").isMissingNode() ? marker : json.path("marker").asLong();
            return new MaxUpdatesResponse(updates, nextMarker);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to get MAX updates", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to get MAX updates", e);
        }
    }

    public record MaxUpdatesResponse(List<JsonNode> updates, Long marker) {
    }
}
