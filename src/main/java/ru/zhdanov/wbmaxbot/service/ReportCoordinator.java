package ru.zhdanov.wbmaxbot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.zhdanov.wbmaxbot.config.AppProperties;
import ru.zhdanov.wbmaxbot.model.AlertTrigger;
import ru.zhdanov.wbmaxbot.model.ChatSubscription;
import ru.zhdanov.wbmaxbot.model.ChatLinkedWbAccount;
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
    private final MaxBotUiService maxBotUiService;
    private final MaxMessagingService maxMessagingService;
    private final VoiceAlertService voiceAlertService;
    private final ChatSettingsService chatSettingsService;
    private final WbAccountService wbAccountService;
    private final ReportRepository reportRepository;
    private final AlertEventRepository alertEventRepository;
    private final ObjectMapper objectMapper;
    private final ZoneId zoneId;

    public ReportCoordinator(AppProperties properties,
                             WildberriesScraper wildberriesScraper,
                             NotificationFormatter notificationFormatter,
                             MaxBotUiService maxBotUiService,
                             MaxMessagingService maxMessagingService,
                             VoiceAlertService voiceAlertService,
                             ChatSettingsService chatSettingsService,
                             WbAccountService wbAccountService,
                             ReportRepository reportRepository,
                             AlertEventRepository alertEventRepository,
                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.wildberriesScraper = wildberriesScraper;
        this.notificationFormatter = notificationFormatter;
        this.maxBotUiService = maxBotUiService;
        this.maxMessagingService = maxMessagingService;
        this.voiceAlertService = voiceAlertService;
        this.chatSettingsService = chatSettingsService;
        this.wbAccountService = wbAccountService;
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
        boolean sessionExists = Files.exists(properties.getWildberries().getStorageStatePath().toAbsolutePath()) || hasAnyConnectedAccount();
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
            boolean sentAny = false;
            for (ChatSubscription chat : targetChats) {
                List<ChatLinkedWbAccount> accounts = wbAccountService.listEnabledAccounts(chat.chatId());
                if (accounts.isEmpty()) {
                    if (manualChatId != null) {
                        maxMessagingService.sendToChat(chat.chatId(), "У этого чата ещё нет подключённых WB аккаунтов. Откройте раздел аккаунтов и авторизуйтесь.");
                    }
                    continue;
                }

                for (ChatLinkedWbAccount account : accounts) {
                    ScrapeResult result = wildberriesScraper.scrapeReport(account.storageStateJson());
                    List<String> reportMessages = notificationFormatter.buildReportMessages(
                            result,
                            properties.getAlert().getMaxRowsInMessage(),
                            maskPhone(account.phoneNumber())
                    );
                    Map<String, String> messageStatuses = sendReportMessages(chat, account, reportMessages, manualChatId == null, result.scrapedAt());
                    long runId = reportRepository.saveSuccessfulRun(result, source, toJson(result.summary()), String.join("\n\n---\n\n", reportMessages));
                    log.info("Stored report run {} for chat {} and account {}", runId, chat.chatId(), account.accountId());
                    processAlerts(result, chat, account, messageStatuses);
                    sentAny = true;
                }
            }

            if (manualChatId != null && !sentAny) {
                maxMessagingService.sendToChat(manualChatId, "Не удалось отправить отчёт: нет доступных WB аккаунтов.");
            }
        } catch (Exception e) {
            log.error("Report execution failed", e);
            reportRepository.saveFailedRun(source, "{\"status\":\"failed\"}", e.getMessage());
            if (manualChatId != null) {
                maxMessagingService.sendToChat(manualChatId, "Не удалось получить отчёт WB: " + e.getMessage());
            }
        }
    }

    private Map<String, String> sendReportMessages(ChatSubscription chat,
                                                   ChatLinkedWbAccount account,
                                                   List<String> reportMessages,
                                                   boolean markAsScheduledSend,
                                                   OffsetDateTime scrapedAt) {
        Map<String, String> statuses = new LinkedHashMap<>();
        int successCount = 0;
        int failureCount = 0;
        for (String message : reportMessages) {
            String status = maxMessagingService.sendToChat(chat.chatId(), message);
            if (status.startsWith("sent")) {
                successCount++;
            } else {
                failureCount++;
            }
        }
        statuses.put(statusKey(chat.chatId(), account.accountId()), "sent=" + successCount + ", failed=" + failureCount);
        if (markAsScheduledSend && failureCount == 0) {
            chatSettingsService.markReportSent(chat.chatId(), scrapedAt);
        }
        return statuses;
    }

    private void processAlerts(ScrapeResult result,
                               ChatSubscription chat,
                               ChatLinkedWbAccount account,
                               Map<String, String> baseMessageStatuses) {
        for (AlertTrigger trigger : evaluateTriggers(result, chat)) {
            String dedupeKey = chat.chatId() + ":" + account.accountId() + ":" + trigger.dedupeKey();
            if (isSuppressedByCooldown(dedupeKey)) {
                continue;
            }

            boolean voiceCallEnabled = properties.getAlert().isVoiceCallEnabled() && chat.callEnabled();
            String alertMessage = notificationFormatter.buildAlertMessage(trigger, voiceCallEnabled, maskPhone(account.phoneNumber()));
            String messageStatus = maxMessagingService.sendToChat(
                    chat.chatId(),
                    maxBotUiService.buildAlertMessage(alertMessage, chat.phoneNumber(), voiceCallEnabled)
            );
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
                    mergeStatuses(baseMessageStatuses.getOrDefault(statusKey(chat.chatId(), account.accountId()), "not-sent"), messageStatus),
                    callResult.provider(),
                    voiceCallEnabled ? (callResult.success() ? "success" : "failed") : "skipped",
                    callResult.externalId(),
                    callResult.details()
            );
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

    private String statusKey(long chatId, long accountId) {
        return chatId + ":" + accountId;
    }

    private boolean hasAnyConnectedAccount() {
        return chatSettingsService.activeChats().stream().anyMatch(chat -> !wbAccountService.listEnabledAccounts(chat.chatId()).isEmpty());
    }

    private String maskPhone(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return "без номера";
        }
        String digits = phoneNumber.replaceAll("[^0-9]", "");
        if (digits.length() < 4) {
            return phoneNumber;
        }
        return "+" + digits.substring(0, Math.min(1, digits.length())) + "***" + digits.substring(digits.length() - 4);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"serialization\":\"failed\"}";
        }
    }
}
