package ru.zhdanov.wbmaxbot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.zhdanov.wbmaxbot.config.AppProperties;
import ru.zhdanov.wbmaxbot.model.AlertTrigger;
import ru.zhdanov.wbmaxbot.model.ReportRow;
import ru.zhdanov.wbmaxbot.model.ScrapeResult;
import ru.zhdanov.wbmaxbot.model.VoiceCallResult;
import ru.zhdanov.wbmaxbot.repository.AlertEventRepository;
import ru.zhdanov.wbmaxbot.repository.ReportRepository;

import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class ReportCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ReportCoordinator.class);

    private final AppProperties properties;
    private final WildberriesScraper wildberriesScraper;
    private final NotificationFormatter notificationFormatter;
    private final MaxMessagingService maxMessagingService;
    private final VoiceAlertService voiceAlertService;
    private final ReportRepository reportRepository;
    private final AlertEventRepository alertEventRepository;
    private final ObjectMapper objectMapper;
    private final ZoneId zoneId;

    public ReportCoordinator(AppProperties properties,
                             WildberriesScraper wildberriesScraper,
                             NotificationFormatter notificationFormatter,
                             MaxMessagingService maxMessagingService,
                             VoiceAlertService voiceAlertService,
                             ReportRepository reportRepository,
                             AlertEventRepository alertEventRepository,
                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.wildberriesScraper = wildberriesScraper;
        this.notificationFormatter = notificationFormatter;
        this.maxMessagingService = maxMessagingService;
        this.voiceAlertService = voiceAlertService;
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
        return notificationFormatter.buildStatusMessage(sessionExists, maxMessagingService.activeChatsCount(), properties.getMode());
    }

    private void executeInternal(String source, Long manualChatId) {
        try {
            ScrapeResult result = wildberriesScraper.scrapeReport();
            List<String> reportMessages = notificationFormatter.buildReportMessages(result, properties.getAlert().getMaxRowsInMessage());
            String messageStatus = "skipped";

            if (manualChatId != null) {
                for (String message : reportMessages) {
                    maxMessagingService.sendToChat(manualChatId, message);
                }
                messageStatus = "manual-chat=" + manualChatId;
            } else if (properties.getAlert().isSendReportEachRun()) {
                messageStatus = maxMessagingService.sendToActiveChats(reportMessages);
            }

            long runId = reportRepository.saveSuccessfulRun(result, source, toJson(result.summary()), String.join("\n\n---\n\n", reportMessages));
            log.info("Stored report run {}", runId);

            processAlerts(result, messageStatus);
        } catch (Exception e) {
            log.error("Report execution failed", e);
            reportRepository.saveFailedRun(source, "{\"status\":\"failed\"}", e.getMessage());
            if (manualChatId != null) {
                maxMessagingService.sendToChat(manualChatId, "Не удалось получить отчёт WB: " + e.getMessage());
            }
        }
    }

    private void processAlerts(ScrapeResult result, String baseMessageStatus) {
        for (AlertTrigger trigger : evaluateTriggers(result)) {
            if (isSuppressedByCooldown(trigger.dedupeKey())) {
                continue;
            }

            String alertMessage = notificationFormatter.buildAlertMessage(trigger);
            String messageStatus = maxMessagingService.sendToActiveChats(List.of(alertMessage));
            VoiceCallResult callResult = voiceAlertService.callAllTargets(notificationFormatter.buildVoiceText(trigger));

            alertEventRepository.save(
                    OffsetDateTime.now(zoneId),
                    trigger.dedupeKey(),
                    trigger.row().route(),
                    trigger.row().parking(),
                    trigger.row().shk(),
                    trigger.row().norm(),
                    trigger.row().ratio(),
                    trigger.reason(),
                    mergeStatuses(baseMessageStatus, messageStatus),
                    callResult.provider(),
                    callResult.success() ? "success" : "failed",
                    callResult.externalId(),
                    callResult.details()
            );
        }
    }

    private List<AlertTrigger> evaluateTriggers(ScrapeResult result) {
        List<AlertTrigger> triggers = new ArrayList<>();
        for (ReportRow row : result.rows()) {
            List<String> reasons = new ArrayList<>();
            if (properties.getAlert().getShkThreshold() > 0 && row.shk() >= properties.getAlert().getShkThreshold()) {
                reasons.add("ШК >= " + properties.getAlert().getShkThreshold());
            }
            if (properties.getAlert().getRatioThreshold() > 0 && row.ratio() >= properties.getAlert().getRatioThreshold()) {
                reasons.add("Заполнение >= " + Math.round(properties.getAlert().getRatioThreshold() * 100) + "%");
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
