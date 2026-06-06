package ru.zhdanov.wbmaxbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MaxUpdateHandler {

    private static final Logger log = LoggerFactory.getLogger(MaxUpdateHandler.class);

    private final MaxMessagingService maxMessagingService;
    private final NotificationFormatter notificationFormatter;
    private final ReportCoordinator reportCoordinator;

    public MaxUpdateHandler(MaxMessagingService maxMessagingService,
                            NotificationFormatter notificationFormatter,
                            ReportCoordinator reportCoordinator) {
        this.maxMessagingService = maxMessagingService;
        this.notificationFormatter = notificationFormatter;
        this.reportCoordinator = reportCoordinator;
    }

    public void handle(JsonNode update) {
        String updateType = update.path("update_type").asText("");
        long chatId = update.path("chat_id").asLong(0);
        Long userId = update.path("user").path("user_id").isMissingNode() ? null : update.path("user").path("user_id").asLong();

        switch (updateType) {
            case "bot_started" -> {
                maxMessagingService.subscribe(chatId, userId, extractTitle(update), extractChatType(update));
                maxMessagingService.sendWelcome(chatId, notificationFormatter.buildWelcomeMessage());
            }
            case "bot_stopped", "bot_removed", "dialog_removed" -> maxMessagingService.deactivate(chatId);
            case "message_created" -> handleMessage(chatId, userId, extractTitle(update), extractChatType(update), extractText(update));
            default -> log.debug("Ignoring MAX update type {}", updateType);
        }
    }

    private void handleMessage(long chatId, Long userId, String title, String chatType, String text) {
        if (chatId <= 0 || text == null || text.isBlank()) {
            return;
        }

        String command = text.trim().toLowerCase();
        switch (command) {
            case "/start", "/help", "/subscribe" -> {
                maxMessagingService.subscribe(chatId, userId, title, chatType);
                maxMessagingService.sendWelcome(chatId, notificationFormatter.buildWelcomeMessage());
            }
            case "/unsubscribe" -> {
                maxMessagingService.deactivate(chatId);
                maxMessagingService.sendToChat(chatId, "Сообщения для этого чата отключены.");
            }
            case "/status" -> maxMessagingService.sendToChat(chatId, reportCoordinator.buildStatusMessage());
            case "/report" -> reportCoordinator.executeManualRun(chatId);
            default -> maxMessagingService.sendToChat(chatId, """
                    Неизвестная команда.
                    Доступно:
                    /report
                    /status
                    /subscribe
                    /unsubscribe
                    /help
                    """.trim());
        }
    }

    private String extractTitle(JsonNode update) {
        if (update.hasNonNull("title")) {
            return update.path("title").asText();
        }
        if (update.path("chat").hasNonNull("title")) {
            return update.path("chat").path("title").asText();
        }
        return "MAX chat " + update.path("chat_id").asText();
    }

    private String extractChatType(JsonNode update) {
        if (update.path("chat").hasNonNull("type")) {
            return update.path("chat").path("type").asText();
        }
        return update.path("is_channel").asBoolean(false) ? "channel" : "dialog";
    }

    private String extractText(JsonNode update) {
        if (update.path("message").path("body").hasNonNull("text")) {
            return update.path("message").path("body").path("text").asText();
        }
        if (update.path("body").hasNonNull("text")) {
            return update.path("body").path("text").asText();
        }
        if (update.hasNonNull("text")) {
            return update.path("text").asText();
        }
        return "";
    }
}
