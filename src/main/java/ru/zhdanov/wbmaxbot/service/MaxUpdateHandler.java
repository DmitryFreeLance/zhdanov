package ru.zhdanov.wbmaxbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.zhdanov.wbmaxbot.model.ChatSubscription;
import ru.zhdanov.wbmaxbot.model.MaxOutgoingMessage;

import java.nio.file.Files;
import java.util.Locale;

@Service
public class MaxUpdateHandler {

    private static final Logger log = LoggerFactory.getLogger(MaxUpdateHandler.class);

    private final MaxMessagingService maxMessagingService;
    private final ReportCoordinator reportCoordinator;
    private final ChatSettingsService chatSettingsService;
    private final MaxBotUiService maxBotUiService;
    private final ru.zhdanov.wbmaxbot.config.AppProperties properties;

    public MaxUpdateHandler(MaxMessagingService maxMessagingService,
                            ReportCoordinator reportCoordinator,
                            ChatSettingsService chatSettingsService,
                            MaxBotUiService maxBotUiService,
                            ru.zhdanov.wbmaxbot.config.AppProperties properties) {
        this.maxMessagingService = maxMessagingService;
        this.reportCoordinator = reportCoordinator;
        this.chatSettingsService = chatSettingsService;
        this.maxBotUiService = maxBotUiService;
        this.properties = properties;
    }

    public void handle(JsonNode update) {
        String updateType = update.path("update_type").asText("");
        long chatId = extractChatId(update);
        Long userId = extractUserId(update);

        switch (updateType) {
            case "bot_started" -> {
                maxMessagingService.subscribe(chatId, userId, extractTitle(update), extractChatType(update));
                maxMessagingService.sendToChat(chatId, maxBotUiService.buildMainMenu(chatSettingsService.getRequired(chatId)));
            }
            case "bot_stopped", "bot_removed", "dialog_removed" -> maxMessagingService.deactivate(chatId);
            case "message_created" -> handleMessage(chatId, userId, extractTitle(update), extractChatType(update), extractText(update));
            case "message_callback" -> handleCallback(chatId, extractCallbackId(update), extractCallbackPayload(update));
            default -> log.debug("Ignoring MAX update type {}", updateType);
        }
    }

    private void handleMessage(long chatId, Long userId, String title, String chatType, String text) {
        if (chatId <= 0) {
            log.warn("Ignoring MAX message_created update without chat. chatId={}", chatId);
            return;
        }

        if (text == null || text.isBlank()) {
            log.warn("Ignoring MAX message_created update without chat/text. chatId={}, textPresent={}", chatId, text != null && !text.isBlank());
            return;
        }

        maxMessagingService.subscribe(chatId, userId, title, chatType);

        String rawText = text.trim();
        String command = rawText.toLowerCase(Locale.ROOT);
        log.info("Handling MAX command '{}' for chat {}", command, chatId);

        ChatSubscription chat = chatSettingsService.getRequired(chatId);
        if (!command.startsWith("/") && chat.pendingInputState() != null && !chat.pendingInputState().isBlank()) {
            handlePendingInput(chat, rawText);
            return;
        }

        switch (command) {
            case "/start", "/help", "/subscribe" -> {
                chatSettingsService.clearPendingInputState(chatId);
                maxMessagingService.sendToChat(chatId, maxBotUiService.buildMainMenu(chatSettingsService.getRequired(chatId)));
            }
            case "/unsubscribe" -> {
                maxMessagingService.deactivate(chatId);
                maxMessagingService.sendToChat(chatId, "Сообщения для этого чата отключены.");
            }
            case "/status" -> maxMessagingService.sendToChat(chatId, buildStatusMenu(chatId));
            case "/report" -> reportCoordinator.executeManualRun(chatId);
            case "/menu" -> maxMessagingService.sendToChat(chatId, maxBotUiService.buildMainMenu(chatSettingsService.getRequired(chatId)));
            default -> maxMessagingService.sendToChat(chatId, maxBotUiService.buildMainMenu(chatSettingsService.getRequired(chatId)));
        }
    }

    private void handleCallback(long chatId, String callbackId, String payload) {
        if (chatId <= 0 || callbackId == null || callbackId.isBlank() || payload == null || payload.isBlank()) {
            log.warn("Ignoring MAX message_callback without required fields. chatId={}, callbackIdPresent={}, payloadPresent={}",
                    chatId, callbackId != null && !callbackId.isBlank(), payload != null && !payload.isBlank());
            return;
        }

        ChatSubscription chat = chatSettingsService.getRequired(chatId);
        switch (payload) {
            case "menu:main" -> maxMessagingService.answerCallback(chatId, callbackId, null, maxBotUiService.buildMainMenu(chat));
            case "menu:interval" -> maxMessagingService.answerCallback(chatId, callbackId, null, maxBotUiService.buildIntervalMenu(chat));
            case "menu:alert" -> maxMessagingService.answerCallback(chatId, callbackId, null, maxBotUiService.buildAlertMenu(chat));
            case "menu:phone" -> maxMessagingService.answerCallback(chatId, callbackId, null, maxBotUiService.buildPhoneMenu(chat));
            case "menu:status" -> maxMessagingService.answerCallback(chatId, callbackId, "Статус обновлён", buildStatusMenu(chatId));
            case "report:now" -> {
                maxMessagingService.answerCallback(chatId, callbackId, "Формирую отчёт...", maxBotUiService.buildMainMenu(chat));
                reportCoordinator.executeManualRun(chatId);
            }
            case "interval:15" -> updateInterval(chatId, callbackId, 15);
            case "interval:30" -> updateInterval(chatId, callbackId, 30);
            case "interval:60" -> updateInterval(chatId, callbackId, 60);
            case "interval:off" -> {
                chatSettingsService.disableAutoReport(chatId);
                maxMessagingService.answerCallback(chatId, callbackId, maxBotUiService.buildIntervalSavedMessage(false, 15),
                        maxBotUiService.buildIntervalMenu(chatSettingsService.getRequired(chatId)));
            }
            case "input:phone" -> {
                chatSettingsService.setPendingInputState(chatId, ChatSettingsService.PENDING_PHONE);
                maxMessagingService.answerCallback(chatId, callbackId, "Жду номер телефона", maxBotUiService.buildPhonePrompt());
            }
            case "input:shk" -> {
                chatSettingsService.setPendingInputState(chatId, ChatSettingsService.PENDING_SHK_THRESHOLD);
                maxMessagingService.answerCallback(chatId, callbackId, "Жду порог ШК", maxBotUiService.buildShkPrompt());
            }
            case "input:ratio" -> {
                chatSettingsService.setPendingInputState(chatId, ChatSettingsService.PENDING_RATIO_THRESHOLD);
                maxMessagingService.answerCallback(chatId, callbackId, "Жду процент заполнения", maxBotUiService.buildRatioPrompt());
            }
            case "phone:clear" -> {
                chatSettingsService.setPhoneNumber(chatId, null);
                chatSettingsService.setCallEnabled(chatId, false);
                maxMessagingService.answerCallback(chatId, callbackId, maxBotUiService.buildPhoneClearedMessage(),
                        maxBotUiService.buildPhoneMenu(chatSettingsService.getRequired(chatId)));
            }
            case "call:toggle" -> toggleCall(chatId, callbackId, chat);
            default -> maxMessagingService.answerCallback(chatId, callbackId, "Неизвестное действие", maxBotUiService.buildMainMenu(chat));
        }
    }

    private void handlePendingInput(ChatSubscription chat, String rawText) {
        long chatId = chat.chatId();
        try {
            switch (chat.pendingInputState()) {
                case ChatSettingsService.PENDING_PHONE -> {
                    String phone = normalizePhoneNumber(rawText);
                    chatSettingsService.setPhoneNumber(chatId, phone);
                    chatSettingsService.clearPendingInputState(chatId);
                    maxMessagingService.sendToChat(chatId, maxBotUiService.buildPhoneSavedMessage(phone));
                    maxMessagingService.sendToChat(chatId, maxBotUiService.buildPhoneMenu(chatSettingsService.getRequired(chatId)));
                }
                case ChatSettingsService.PENDING_SHK_THRESHOLD -> {
                    Integer threshold = parseShkThreshold(rawText);
                    chatSettingsService.setShkThreshold(chatId, threshold);
                    chatSettingsService.clearPendingInputState(chatId);
                    maxMessagingService.sendToChat(chatId, maxBotUiService.buildShkSavedMessage(threshold));
                    maxMessagingService.sendToChat(chatId, maxBotUiService.buildAlertMenu(chatSettingsService.getRequired(chatId)));
                }
                case ChatSettingsService.PENDING_RATIO_THRESHOLD -> {
                    Double threshold = parseRatioThreshold(rawText);
                    chatSettingsService.setRatioThreshold(chatId, threshold);
                    chatSettingsService.clearPendingInputState(chatId);
                    maxMessagingService.sendToChat(chatId, maxBotUiService.buildRatioSavedMessage(threshold));
                    maxMessagingService.sendToChat(chatId, maxBotUiService.buildAlertMenu(chatSettingsService.getRequired(chatId)));
                }
                default -> {
                    chatSettingsService.clearPendingInputState(chatId);
                    maxMessagingService.sendToChat(chatId, maxBotUiService.buildUnknownInputMessage());
                    maxMessagingService.sendToChat(chatId, maxBotUiService.buildMainMenu(chatSettingsService.getRequired(chatId)));
                }
            }
        } catch (IllegalArgumentException e) {
            maxMessagingService.sendToChat(chatId, e.getMessage());
        }
    }

    private void updateInterval(long chatId, String callbackId, int intervalMinutes) {
        chatSettingsService.setReportInterval(chatId, intervalMinutes);
        maxMessagingService.answerCallback(chatId, callbackId, maxBotUiService.buildIntervalSavedMessage(true, intervalMinutes),
                maxBotUiService.buildIntervalMenu(chatSettingsService.getRequired(chatId)));
    }

    private void toggleCall(long chatId, String callbackId, ChatSubscription chat) {
        if (!chat.callEnabled() && (chat.phoneNumber() == null || chat.phoneNumber().isBlank())) {
            maxMessagingService.answerCallback(chatId, callbackId, maxBotUiService.buildCallToggleBlockedMessage(),
                    maxBotUiService.buildPhoneMenu(chat));
            return;
        }

        chatSettingsService.setCallEnabled(chatId, !chat.callEnabled());
        ChatSubscription updatedChat = chatSettingsService.getRequired(chatId);
        maxMessagingService.answerCallback(chatId, callbackId, maxBotUiService.buildCallToggleMessage(updatedChat.callEnabled()),
                maxBotUiService.buildPhoneMenu(updatedChat));
    }

    private MaxOutgoingMessage buildStatusMenu(long chatId) {
        ChatSubscription chat = chatSettingsService.getRequired(chatId);
        boolean sessionExists = Files.exists(properties.getWildberries().getStorageStatePath().toAbsolutePath());
        return maxBotUiService.buildStatusMenu(chat, sessionExists, properties.getMode(), maxMessagingService.activeChatsCount());
    }

    private String normalizePhoneNumber(String rawText) {
        String digits = rawText.replaceAll("[^0-9+]", "");
        if (digits.startsWith("+")) {
            String normalized = "+" + digits.substring(1).replaceAll("\\D", "");
            if (normalized.length() < 11 || normalized.length() > 16) {
                throw new IllegalArgumentException("Некорректный номер. Отправьте номер в формате +79991234567.");
            }
            return normalized;
        }

        String onlyDigits = digits.replaceAll("\\D", "");
        if (onlyDigits.length() == 10) {
            return "+7" + onlyDigits;
        }
        if (onlyDigits.length() == 11 && (onlyDigits.startsWith("7") || onlyDigits.startsWith("8"))) {
            return "+7" + onlyDigits.substring(1);
        }
        throw new IllegalArgumentException("Некорректный номер. Отправьте номер в формате +79991234567.");
    }

    private Integer parseShkThreshold(String rawText) {
        try {
            int value = Integer.parseInt(rawText.trim());
            if (value < 0) {
                throw new IllegalArgumentException("Порог ШК не может быть отрицательным.");
            }
            return value == 0 ? null : value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Введите целое число, например 1200.");
        }
    }

    private Double parseRatioThreshold(String rawText) {
        String trimmed = rawText.trim();
        String normalized = trimmed.replace("%", "").replace(",", ".");
        try {
            double value = Double.parseDouble(normalized);
            if (value < 0) {
                throw new IllegalArgumentException("Процент не может быть отрицательным.");
            }
            if (value == 0) {
                return null;
            }
            boolean looksLikePercent = !trimmed.contains(".") && !trimmed.contains(",");
            if (looksLikePercent || value > 1) {
                value = value / 100.0d;
            }
            if (value <= 0 || value > 1) {
                throw new IllegalArgumentException("Введите значение от 1 до 100, например 90.");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Введите процент числом, например 90.");
        }
    }

    private String extractTitle(JsonNode update) {
        if (update.hasNonNull("title")) {
            return update.path("title").asText();
        }
        if (update.path("chat").hasNonNull("title")) {
            return update.path("chat").path("title").asText();
        }
        if (update.path("message").path("recipient").hasNonNull("chat_id")) {
            return "MAX chat " + update.path("message").path("recipient").path("chat_id").asText();
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

    private String extractCallbackId(JsonNode update) {
        if (update.path("callback").hasNonNull("callback_id")) {
            return update.path("callback").path("callback_id").asText();
        }
        return "";
    }

    private String extractCallbackPayload(JsonNode update) {
        if (update.path("callback").hasNonNull("payload")) {
            return update.path("callback").path("payload").asText();
        }
        if (update.hasNonNull("payload")) {
            return update.path("payload").asText();
        }
        return "";
    }

    private long extractChatId(JsonNode update) {
        if (update.hasNonNull("chat_id")) {
            return update.path("chat_id").asLong(0);
        }
        if (update.path("message").path("recipient").hasNonNull("chat_id")) {
            return update.path("message").path("recipient").path("chat_id").asLong(0);
        }
        if (update.path("recipient").hasNonNull("chat_id")) {
            return update.path("recipient").path("chat_id").asLong(0);
        }
        return 0;
    }

    private Long extractUserId(JsonNode update) {
        if (update.path("user").hasNonNull("user_id")) {
            return update.path("user").path("user_id").asLong();
        }
        if (update.path("message").path("sender").hasNonNull("user_id")) {
            return update.path("message").path("sender").path("user_id").asLong();
        }
        if (update.path("sender").hasNonNull("user_id")) {
            return update.path("sender").path("user_id").asLong();
        }
        return null;
    }
}
