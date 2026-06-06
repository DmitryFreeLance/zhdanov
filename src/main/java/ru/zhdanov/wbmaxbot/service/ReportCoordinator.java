package ru.zhdanov.wbmaxbot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.zhdanov.wbmaxbot.config.AppProperties;
import ru.zhdanov.wbmaxbot.model.AlertTrigger;
import ru.zhdanov.wbmaxbot.model.ChatSubscription;
import ru.zhdanov.wbmaxbot.model.ReportRow;
import ru.zhdanov.wbmaxbot.model.ScrapeResult;
import ru.zhdanov.wbmaxbot.model.VoiceCallResult;
import ru.zhdanov.wbmaxbot.repository.AlertEventRepository;
import ru.zhdanov.wbmaxbot.repository.ReportRepository;

import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ReportCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ReportCoordinator.class);

    private final AppProperties properties;
    private final WildberriesScraper wildberriesScraper;
    private final NotificationFormatter notificationFormatter;
    private final MaxMessagingService maxMessagingService;
    private final VoiceAlertService voiceAlertService;
    private final ChatSettingsService chatSettingsService;
    private final ReportRepository reportRepository;
    private final AlertEventRepository alertEventRepository;
    private final ObjectMapper objectMapper;
    private final ZoneId zoneId;

    public ReportCoordinator(AppProperties properties,
                             WildberriesScraper wildberriesScraper,
                             NotificationFormatter notificationFormatter,
                             MaxMessagingService maxMessagingService,
                             VoiceAlertService voiceAlertService,
                             ChatSettingsService chatSettingsService,
                             ReportRepository reportRepository,
                             AlertEventRepository alertEventRepository,
                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.wildberriesScraper = wildberriesScraper;
        this.notificationFormatter = notificationFormatter;
        this.maxMessagingService = maxMessagingService;
        this.voiceAlertService = voiceAlertService;
        this.chatSettingsService = chatSettingsService;
        this.reportRepository = reportRepository;
        this.alertEventRepository = alertEventRepository;
        this.objectMapper = objectMapper;
        this.zoneId = ZoneId.of(properties.getZoneId());
    }

    public void executeScheduledRun() {
        executeInternal("scheduler", null);
    }

    public void executeManualRun(Long chatId) {
        executeInternal("manual", chatId);
    }

    public String buildStatusMessage() {
        boolean sessionExists = Files.exists(properties.getWildberries().getStorageStatePath().toAbsolutePath());
        return notificationFormatter.buildStatusMessage(
                sessionExists,
                maxMessagingService.activeChatsCount(),
                properties.getMode(),
                properties.getAlert().isVoiceCallEnabled()
        );
    }

    private void executeInternal(String source, Long manualChatId) {
        List<ChatSubscription> targetChats = manualChatId != null
                ? List.of(chatSettingsService.getRequired(manualChatId))
                : chatSettingsService.findChatsDueForAutoReport(OffsetDateTime.now(zoneId));
        if (targetChats.isEmpty()) {
            return;
        }

        try {
            ScrapeResult result = wildberriesScraper.scrapeReport();
            List<String> reportMessages = notificationFormatter.buildReportMessages(result, properties.getAlert().getMaxRowsInMessage());
            Map<Long, String> messageStatuses = new LinkedHashMap<>();

            messageStatuses = sendReportMessages(targetChats, reportMessages, manualChatId == null, result.scrapedAt());

            long runId = reportRepository.saveSuccessfulRun(result, source, toJson(result.summary()), String.join("\n\n---\n\n", reportMessages));
            log.info("Stored report run {}", runId);

            processAlerts(result, targetChats, messageStatuses);
        } catch (Exception e) {
            log.error("Report execution failed", e);
            reportRepository.saveFailedRun(source, "{\"status\":\"failed\"}", e.getMessage());
            if (manualChatId != null) {
                maxMessagingService.sendToChat(manualChatId, "Не удалось получить отчёт WB: " + e.getMessage());
            }
        }
    }

    private Map<Long, String> sendReportMessages(List<ChatSubscription> targetChats,
                                                 List<String> reportMessages,
                                                 boolean markAsScheduledSend,
                                                 OffsetDateTime scrapedAt) {
        Map<Long, String> statuses = new LinkedHashMap<>();
        for (ChatSubscription chat : targetChats) {
            String status = "sent=0, failed=0";
            int successCount = 0;
            int failureCount = 0;
            for (String message : reportMessages) {
                status = maxMessagingService.sendToChat(chat.chatId(), message);
                if (status.startsWith("sent")) {
                    successCount++;
                } else {
                    failureCount++;
                }
            }
            statuses.put(chat.chatId(), "sent=" + successCount + ", failed=" + failureCount);
            if (markAsScheduledSend && failureCount == 0) {
                chatSettingsService.markReportSent(chat.chatId(), scrapedAt);
            }
        }
        return statuses;
    }

    private void processAlerts(ScrapeResult result, List<ChatSubscription> targetChats, Map<Long, String> baseMessageStatuses) {
        for (ChatSubscription chat : targetChats) {
            for (AlertTrigger trigger : evaluateTriggers(result, chat)) {
                String dedupeKey = chat.chatId() + ":" + trigger.dedupeKey();
                if (isSuppressedByCooldown(dedupeKey)) {
                    continue;
                }

                boolean voiceCallEnabled = properties.getAlert().isVoiceCallEnabled() && chat.callEnabled();
                String alertMessage = notificationFormatter.buildAlertMessage(trigger, voiceCallEnabled);
                String messageStatus = maxMessagingService.sendToChat(chat.chatId(), alertMessage);
                VoiceCallResult callResult = voiceCallEnabled
                        ? voiceAlertService.callTarget(chat.phoneNumber(), notificationFormatter.buildVoiceText(trigger))
                        : VoiceCallResult.success("reminder-only", null, "Voice calls disabled; sent manual call reminder instead");

                alertEventRepository.save(
                        OffsetDateTime.now(zoneId),
                        dedupeKey,
                        trigger.row().route(),
                        trigger.row().parking(),
                        trigger.row().shk(),
                        trigger.row().norm(),
                        trigger.row().ratio(),
                        trigger.reason(),
                        mergeStatuses(baseMessageStatuses.getOrDefault(chat.chatId(), "not-sent"), messageStatus),
                        callResult.provider(),
                        voiceCallEnabled ? (callResult.success() ? "success" : "failed") : "skipped",
                        callResult.externalId(),
                        callResult.details()
                );
            }
        }
    }

    private List<AlertTrigger> evaluateTriggers(ScrapeResult result, ChatSubscription chat) {
        List<AlertTrigger> triggers = new ArrayList<>();
        for (ReportRow row : result.rows()) {
            List<String> reasons = new ArrayList<>();
            if (chat.shkThreshold() != null && chat.shkThreshold() > 0 && row.shk() >= chat.shkThreshold()) {
                reasons.add("ШК >= " + chat.shkThreshold());
            }
            if (chat.ratioThreshold() != null && chat.ratioThreshold() > 0 && row.ratio() >= chat.ratioThreshold()) {
                reasons.add("Заполнение >= " + Math.round(chat.ratioThreshold() * 100) + "%");
            }
            if (!reasons.isEmpty()) {
                triggers.add(new AlertTrigger(row.route() + "#" + row.parking(), row, String.join(", ", reasons)));
            }
        }
        return triggers;
    }

    private boolean isSuppressedByCooldown(String dedupeKey) {
        Optional<OffsetDateTime> lastAlertAt = alertEventRepository.findLastAlertAt(dedupeKey);
        return lastAlertAt
                .map(last -> last.plus(properties.getAlert().getCooldown()).isAfter(OffsetDateTime.now(zoneId)))
                .orElse(false);
    }

    private String mergeStatuses(String baseStatus, String alertStatus) {
        return baseStatus + "; alert=" + alertStatus;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"serialization\":\"failed\"}";
        }
    }
}
