package ru.zhdanov.wbmaxbot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import ru.zhdanov.wbmaxbot.config.AppProperties;
import ru.zhdanov.wbmaxbot.model.AlertTrigger;
import ru.zhdanov.wbmaxbot.model.ChatSubscription;
import ru.zhdanov.wbmaxbot.model.ChatLinkedWbAccount;
import ru.zhdanov.wbmaxbot.model.ReportRow;
import ru.zhdanov.wbmaxbot.model.ScrapeResult;
import ru.zhdanov.wbmaxbot.model.VoiceCallResult;
import ru.zhdanov.wbmaxbot.repository.AlertEventRepository;
import ru.zhdanov.wbmaxbot.repository.ReportRepository;
import ru.zhdanov.wbmaxbot.repository.VoiceCallAttemptRepository;

import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ReportCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ReportCoordinator.class);
    private static final int MANUAL_REPORT_TIMEOUT_SECONDS = 120;
    private static final int SCHEDULED_REPORT_TIMEOUT_SECONDS = 180;
    private static final int SCHEDULED_ACCOUNT_TIMEOUT_SECONDS = 90;
    private static final int SCRAPE_ATTEMPTS = 2;

    private final AppProperties properties;
    private final WildberriesScraper wildberriesScraper;
    private final NotificationFormatter notificationFormatter;
    private final MaxBotUiService maxBotUiService;
    private final MaxMessagingService maxMessagingService;
    private final VoiceAlertService voiceAlertService;
    private final VoiceCallFollowUpService voiceCallFollowUpService;
    private final VoiceCallPolicyService voiceCallPolicyService;
    private final PhoneBlacklistService phoneBlacklistService;
    private final ChatSettingsService chatSettingsService;
    private final WbAccountService wbAccountService;
    private final ReportRepository reportRepository;
    private final AlertEventRepository alertEventRepository;
    private final VoiceCallAttemptRepository voiceCallAttemptRepository;
    private final ObjectMapper objectMapper;
    private final ZoneId zoneId;
    private final ExecutorService scheduledReportExecutor;
    private final ExecutorService scheduledAccountExecutor;
    private final ExecutorService manualReportExecutor;
    private final ScheduledExecutorService reportWatchdogExecutor;
    private final Map<Long, ManualRunState> manualRunStates;
    private final AtomicBoolean scheduledRunInProgress;
    private final AtomicReference<Future<?>> scheduledRunFuture;

    public ReportCoordinator(AppProperties properties,
                             WildberriesScraper wildberriesScraper,
                             NotificationFormatter notificationFormatter,
                             MaxBotUiService maxBotUiService,
                             MaxMessagingService maxMessagingService,
                             VoiceAlertService voiceAlertService,
                             VoiceCallFollowUpService voiceCallFollowUpService,
                             VoiceCallPolicyService voiceCallPolicyService,
                             PhoneBlacklistService phoneBlacklistService,
                             ChatSettingsService chatSettingsService,
                             WbAccountService wbAccountService,
                             ReportRepository reportRepository,
                             AlertEventRepository alertEventRepository,
                             VoiceCallAttemptRepository voiceCallAttemptRepository,
                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.wildberriesScraper = wildberriesScraper;
        this.notificationFormatter = notificationFormatter;
        this.maxBotUiService = maxBotUiService;
        this.maxMessagingService = maxMessagingService;
        this.voiceAlertService = voiceAlertService;
        this.voiceCallFollowUpService = voiceCallFollowUpService;
        this.voiceCallPolicyService = voiceCallPolicyService;
        this.phoneBlacklistService = phoneBlacklistService;
        this.chatSettingsService = chatSettingsService;
        this.wbAccountService = wbAccountService;
        this.reportRepository = reportRepository;
        this.alertEventRepository = alertEventRepository;
        this.voiceCallAttemptRepository = voiceCallAttemptRepository;
        this.objectMapper = objectMapper;
        this.zoneId = ZoneId.of(properties.getZoneId());
        this.manualRunStates = new ConcurrentHashMap<>();
        this.scheduledRunInProgress = new AtomicBoolean(false);
        this.scheduledRunFuture = new AtomicReference<>();
        this.scheduledReportExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            private final AtomicInteger index = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "wb-scheduled-report-" + index.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        });
        this.scheduledAccountExecutor = Executors.newCachedThreadPool(new ThreadFactory() {
            private final AtomicInteger index = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "wb-scheduled-account-" + index.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        });
        this.manualReportExecutor = Executors.newFixedThreadPool(4, new ThreadFactory() {
            private final AtomicInteger index = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "wb-manual-report-" + index.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        });
        this.reportWatchdogExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "wb-report-watchdog");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public void executeScheduledRun() {
        if (!scheduledRunInProgress.compareAndSet(false, true)) {
            log.info("Skipping scheduled run because previous scheduled report run is still in progress");
            return;
        }
        Future<?> future = scheduledReportExecutor.submit(() -> {
            try {
                executeInternal("scheduler", null, null);
            } finally {
                scheduledRunFuture.set(null);
                scheduledRunInProgress.set(false);
            }
        });
        scheduledRunFuture.set(future);
        reportWatchdogExecutor.schedule(() -> handleScheduledRunTimeout(future), SCHEDULED_REPORT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    public void executeManualRun(Long chatId) {
        if (chatId == null) {
            manualReportExecutor.submit(() -> executeInternal("manual", null, null));
            return;
        }
        ManualRunState state = new ManualRunState();
        ManualRunState existingState = manualRunStates.putIfAbsent(chatId, state);
        if (existingState != null) {
            log.info("Manual report request ignored for chat {} because another manual run is still tracked. blocking={}, timedOut={}, completed={}",
                    chatId, existingState.blocking, existingState.timedOut, existingState.completed);
            return;
        }
        log.info("Manual report requested for chat {}", chatId);
        reportWatchdogExecutor.schedule(() -> handleManualRunTimeout(chatId, state), MANUAL_REPORT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Future<?> future = manualReportExecutor.submit(() -> {
            try {
                executeInternal("manual", chatId, state);
            } finally {
                state.completed = true;
                manualRunStates.remove(chatId, state);
            }
        });
        state.future = future;
    }

    public boolean isManualRunInProgress(long chatId) {
        ManualRunState state = manualRunStates.get(chatId);
        return state != null && state.blocking;
    }

    @PreDestroy
    public void shutdownExecutor() {
        scheduledReportExecutor.shutdownNow();
        scheduledAccountExecutor.shutdownNow();
        manualReportExecutor.shutdownNow();
        reportWatchdogExecutor.shutdownNow();
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

    private void executeInternal(String source, Long manualChatId, ManualRunState manualState) {
        List<ChatSubscription> targetChats = manualChatId != null
                ? List.of(chatSettingsService.getRequired(manualChatId))
                : chatSettingsService.findChatsDueForAutoReport(OffsetDateTime.now(zoneId));
        if (targetChats.isEmpty()) {
            return;
        }

        if (manualChatId == null) {
            log.info("Starting scheduled report run for {} due chats", targetChats.size());
        }

        try {
            boolean sentAny = false;
            if (manualChatId == null) {
                sentAny = executeScheduledChats(source, targetChats);
                return;
            }

            for (ChatSubscription chat : targetChats) {
                List<ChatLinkedWbAccount> accounts = wbAccountService.listEnabledAccounts(chat.chatId());
                if (accounts.isEmpty()) {
                    if (manualChatId != null) {
                        maxMessagingService.sendToChat(chat.chatId(),
                                maxBotUiService.buildErrorMessage("У этого чата ещё нет подключённых WB аккаунтов. Откройте раздел аккаунтов и авторизуйтесь."));
                    }
                    continue;
                }

                for (ChatLinkedWbAccount account : accounts) {
                    try {
                        ScrapeResult result = scrapeReportWithRetry(account, manualState);
                        List<String> reportMessages = notificationFormatter.buildReportMessages(
                                result,
                                properties.getAlert().getMaxRowsInMessage(),
                                maskPhone(account.phoneNumber())
                        );
                        Map<String, String> messageStatuses = sendReportMessages(
                                chat,
                                account,
                                reportMessages,
                                manualChatId == null,
                                result.scrapedAt(),
                                manualState
                        );
                        long runId = reportRepository.saveSuccessfulRun(result, source, toJson(result.summary()), String.join("\n\n---\n\n", reportMessages));
                        log.info("Stored report run {} for chat {} and account {}", runId, chat.chatId(), account.accountId());
                        if (!isManualTimedOut(manualState)) {
                            processAlerts(result, chat, account, messageStatuses);
                            sentAny = true;
                        }
                    } catch (Exception accountError) {
                        log.error("Report execution failed for chat {} and account {}", chat.chatId(), account.accountId(), accountError);
                        reportRepository.saveFailedRun(source, "{\"status\":\"failed\"}", accountError.getMessage());
                        if (manualChatId != null && !isManualTimedOut(manualState)) {
                            maxMessagingService.sendToChat(chat.chatId(),
                                    maxBotUiService.buildErrorMessage(
                                            "Не удалось получить отчёт для аккаунта " + maskPhone(account.phoneNumber()) + ": " + accountError.getMessage()
                                    ));
                        }
                    }
                }
            }

            if (manualChatId != null && !sentAny && !isManualTimedOut(manualState)) {
                maxMessagingService.sendToChat(manualChatId,
                        maxBotUiService.buildErrorMessage("Не удалось отправить отчёт: нет доступных WB аккаунтов."));
            }
        } catch (Exception e) {
            log.error("Report execution failed", e);
            reportRepository.saveFailedRun(source, "{\"status\":\"failed\"}", e.getMessage());
            if (manualChatId != null && !isManualTimedOut(manualState)) {
                maxMessagingService.sendToChat(manualChatId, maxBotUiService.buildErrorMessage("Не удалось получить отчёт WB: " + e.getMessage()));
            }
        }
    }

    private boolean executeScheduledChats(String source, List<ChatSubscription> targetChats) {
        Map<Long, ScheduledAccountDispatch> dispatches = new LinkedHashMap<>();
        Map<Long, ChatSubscription> chatsById = new HashMap<>();

        for (ChatSubscription chat : targetChats) {
            chatsById.put(chat.chatId(), chat);
            List<ChatLinkedWbAccount> accounts = wbAccountService.listEnabledAccounts(chat.chatId());
            if (accounts.isEmpty()) {
                continue;
            }
            for (ChatLinkedWbAccount account : accounts) {
                ScheduledAccountDispatch dispatch = dispatches.computeIfAbsent(
                        account.accountId(),
                        ignored -> new ScheduledAccountDispatch(account)
                );
                dispatch.chatIds.add(chat.chatId());
            }
        }

        if (dispatches.isEmpty()) {
            return false;
        }

        log.info("Scheduled report run will process {} unique WB accounts for {} due chats",
                dispatches.size(), targetChats.size());

        boolean sentAny = false;
        for (ScheduledAccountDispatch dispatch : dispatches.values()) {
            ChatLinkedWbAccount account = dispatch.account;
            try {
                ScrapeResult result = scrapeScheduledReportWithTimeout(account);
                List<String> reportMessages = notificationFormatter.buildReportMessages(
                        result,
                        properties.getAlert().getMaxRowsInMessage(),
                        maskPhone(account.phoneNumber())
                );
                String summaryJson = toJson(result.summary());
                String reportText = String.join("\n\n---\n\n", reportMessages);

                for (Long chatId : dispatch.chatIds) {
                    ChatSubscription chat = chatsById.get(chatId);
                    if (chat == null) {
                        continue;
                    }
                    Map<String, String> messageStatuses = sendReportMessages(
                            chat,
                            account,
                            reportMessages,
                            true,
                            result.scrapedAt(),
                            null
                    );
                    long runId = reportRepository.saveSuccessfulRun(result, source, summaryJson, reportText);
                    log.info("Stored report run {} for chat {} and account {}", runId, chat.chatId(), account.accountId());
                    processAlerts(result, chat, account, messageStatuses);
                    sentAny = true;
                }
            } catch (Exception accountError) {
                log.error("Scheduled report execution failed for shared account {}", account.accountId(), accountError);
                reportRepository.saveFailedRun(source, "{\"status\":\"failed\"}", accountError.getMessage());
            }
        }

        return sentAny;
    }

    private ScrapeResult scrapeScheduledReportWithTimeout(ChatLinkedWbAccount account) {
        Future<ScrapeResult> future = scheduledAccountExecutor.submit(() -> scrapeReportWithRetry(account, null));
        try {
            return future.get(SCHEDULED_ACCOUNT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException(
                    "WB scrape timed out for scheduled run after " + SCHEDULED_ACCOUNT_TIMEOUT_SECONDS + " seconds"
            );
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Scheduled report run interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("WB scrape failed during scheduled run", cause);
        }
    }

    private Map<String, String> sendReportMessages(ChatSubscription chat,
                                                   ChatLinkedWbAccount account,
                                                   List<String> reportMessages,
                                                   boolean markAsScheduledSend,
                                                   OffsetDateTime scrapedAt,
                                                   ManualRunState manualState) {
        Map<String, String> statuses = new LinkedHashMap<>();
        if (isManualTimedOut(manualState)) {
            statuses.put(statusKey(chat.chatId(), account.accountId()), "skipped: manual-timeout");
            return statuses;
        }
        int successCount = 0;
        int failureCount = 0;
        for (int i = 0; i < reportMessages.size(); i++) {
            String message = reportMessages.get(i);
            boolean lastMessage = i == reportMessages.size() - 1;
            String status = lastMessage
                    ? maxMessagingService.sendToChat(chat.chatId(), maxBotUiService.buildMenuMessage(message))
                    : maxMessagingService.sendToChat(chat.chatId(), message);
            if (status.startsWith("sent")) {
                successCount++;
            } else {
                failureCount++;
                log.warn("Failed to deliver report message for chat {} and account {}. part={}/{} status={}",
                        chat.chatId(), account.accountId(), i + 1, reportMessages.size(), status);
            }
        }
        log.info("Report delivery status for chat {} and account {}: sent={}, failed={}, parts={}",
                chat.chatId(), account.accountId(), successCount, failureCount, reportMessages.size());
        statuses.put(statusKey(chat.chatId(), account.accountId()), "sent=" + successCount + ", failed=" + failureCount);
        if (markAsScheduledSend && failureCount == 0) {
            chatSettingsService.markReportSent(chat.chatId(), scrapedAt);
        }
        return statuses;
    }

    private void handleManualRunTimeout(long chatId, ManualRunState state) {
        if (state.completed || !state.blocking) {
            return;
        }
        state.blocking = false;
        state.timedOut = true;
        manualRunStates.remove(chatId, state);
        Future<?> future = state.future;
        if (future != null) {
            future.cancel(true);
        }
        log.warn("Manual report timed out for chat {} after {} seconds", chatId, MANUAL_REPORT_TIMEOUT_SECONDS);
        maxMessagingService.sendToChat(chatId, maxBotUiService.buildReportTimedOutMessage());
    }

    private void handleScheduledRunTimeout(Future<?> future) {
        if (future == null || future.isDone()) {
            return;
        }
        if (!scheduledRunFuture.compareAndSet(future, null)) {
            return;
        }
        future.cancel(true);
        scheduledRunInProgress.set(false);
        log.warn("Scheduled report run timed out after {} seconds and was cancelled", SCHEDULED_REPORT_TIMEOUT_SECONDS);
    }

    private boolean isManualTimedOut(ManualRunState state) {
        return state != null && state.timedOut;
    }

    private ScrapeResult scrapeReportWithRetry(ChatLinkedWbAccount account, ManualRunState manualState) {
        RuntimeException lastRuntime = null;
        IllegalStateException lastState = null;
        for (int attempt = 1; attempt <= SCRAPE_ATTEMPTS; attempt++) {
            if (isManualTimedOut(manualState) || Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Report run interrupted");
            }
            try {
                return wildberriesScraper.scrapeReport(account.storageStateJson());
            } catch (IllegalStateException e) {
                lastState = e;
                if (!isRetryableScrapeException(e) || attempt >= SCRAPE_ATTEMPTS) {
                    throw e;
                }
                log.warn("Retrying WB scrape for account {} after transient state error. attempt={}/{} error={}",
                        account.accountId(), attempt, SCRAPE_ATTEMPTS, e.getMessage());
            } catch (RuntimeException e) {
                lastRuntime = e;
                if (!isRetryableScrapeException(e) || attempt >= SCRAPE_ATTEMPTS) {
                    throw e;
                }
                log.warn("Retrying WB scrape for account {} after transient runtime error. attempt={}/{} error={}",
                        account.accountId(), attempt, SCRAPE_ATTEMPTS, e.getMessage());
            }
        }
        if (lastState != null) {
            throw lastState;
        }
        throw lastRuntime == null ? new IllegalStateException("WB scrape failed") : lastRuntime;
    }

    private boolean isRetryableScrapeException(Throwable error) {
        if (error == null) {
            return false;
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        return message.contains("Failed to read message")
                || message.contains("Execution context was destroyed")
                || message.contains("Target page, context or browser has been closed")
                || message.contains("Most likely the page has been closed")
                || message.startsWith("Timed out waiting for WB report table")
                || message.startsWith("WB report page did not open in time");
    }

    private void processAlerts(ScrapeResult result,
                               ChatSubscription chat,
                               ChatLinkedWbAccount account,
                               Map<String, String> baseMessageStatuses) {
        List<AlertTrigger> activeTriggers = new ArrayList<>();
        for (AlertTrigger trigger : evaluateTriggers(result, chat)) {
            String dedupeKey = chat.chatId() + ":" + account.accountId() + ":" + trigger.dedupeKey();
            if (isSuppressedByCooldown(dedupeKey)) {
                continue;
            }
            activeTriggers.add(trigger);
        }

        if (activeTriggers.isEmpty()) {
            return;
        }

        boolean voiceCallEnabled = properties.getAlert().isVoiceCallEnabled()
                && chat.callEnabled()
                && isVoiceAllowedFor(chat);
        boolean blacklistedPhone = voiceCallEnabled && phoneBlacklistService.isBlacklisted(chat.phoneNumber());
        if (blacklistedPhone) {
            maxMessagingService.sendToChat(chat.chatId(),
                    maxBotUiService.buildErrorMessage(phoneBlacklistService.buildBlockedAutoCallMessage()));
            voiceCallEnabled = false;
        }
        String voiceText = shouldUseSilentExolveMessage()
                ? ""
                : notificationFormatter.buildVoiceText(activeTriggers);
        boolean callFlowReserved = false;
        Long attemptId = null;
        VoiceCallResult callResult;
        if (!voiceCallEnabled) {
            callResult = VoiceCallResult.success("reminder-only", null, "Voice calls disabled; sent manual call reminder instead");
        } else {
            VoiceCallPolicyService.AutoCallDecision callDecision = voiceCallPolicyService.evaluate(chat, account.accountId(), OffsetDateTime.now(zoneId));
            if (!callDecision.allowed()) {
                voiceCallEnabled = false;
                callResult = VoiceCallResult.success("reminder-only", null, callDecision.reason());
            } else if (!voiceCallFollowUpService.tryBeginCallFlow(chat.chatId())) {
                log.info("Skipping voice call because previous follow-up is still active. chatId={}, phone={}",
                        chat.chatId(), chat.phoneNumber());
                callResult = VoiceCallResult.failure(properties.getTelephony().getProvider(),
                        "Previous voice call follow-up is still in progress");
            } else {
                callFlowReserved = true;
                callResult = voiceAlertService.callTarget(chat.phoneNumber(), voiceText);
                attemptId = voiceCallAttemptRepository.createAttempt(
                        OffsetDateTime.now(zoneId),
                        chat.chatId(),
                        account.accountId(),
                        "auto",
                        chat.phoneNumber(),
                        callResult.provider(),
                        callResult.externalId(),
                        callResult.success() ? "started" : "failed_start",
                        callResult.success(),
                        callResult.details()
                );
            }
        }

        for (AlertTrigger trigger : activeTriggers) {
            String alertMessage = notificationFormatter.buildAlertMessage(trigger, voiceCallEnabled, maskPhone(account.phoneNumber()));
            String messageStatus = maxMessagingService.sendToChat(
                    chat.chatId(),
                    maxBotUiService.buildAlertMessage(alertMessage, chat.phoneNumber(), voiceCallEnabled)
            );

            String dedupeKey = chat.chatId() + ":" + account.accountId() + ":" + trigger.dedupeKey();
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

        if (voiceCallEnabled && callFlowReserved) {
            voiceCallFollowUpService.sendCallResultAsync(chat.chatId(), account.accountId(), chat.phoneNumber(), callResult, voiceText, attemptId);
        }
    }

    private boolean shouldUseSilentExolveMessage() {
        return "exolve".equalsIgnoreCase(properties.getTelephony().getProvider())
                && properties.getTelephony().getExolve().getServiceId() != null
                && !properties.getTelephony().getExolve().getServiceId().isBlank();
    }

    private List<AlertTrigger> evaluateTriggers(ScrapeResult result, ChatSubscription chat) {
        List<AlertTrigger> triggers = new ArrayList<>();
        for (ReportRow row : result.rows()) {
            if (!matchesAlertParking(chat.alertParking(), row.parking())) {
                continue;
            }
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

    private boolean matchesAlertParking(String selectedParking, String rowParking) {
        if (selectedParking == null || selectedParking.isBlank()) {
            return true;
        }
        return normalizeParking(selectedParking).equals(normalizeParking(rowParking));
    }

    private String normalizeParking(String parking) {
        if (parking == null) {
            return "";
        }
        String normalized = parking.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        normalized = normalized.replaceFirst("^парковка\\s*", "");
        normalized = normalized.replaceFirst("^№\\s*", "");
        normalized = normalized.replaceFirst("^#\\s*", "");
        return normalized.trim();
    }

    private boolean isSuppressedByCooldown(String dedupeKey) {
        Optional<OffsetDateTime> lastAlertAt = alertEventRepository.findLastAlertAt(dedupeKey);
        return lastAlertAt
                .map(last -> last.plus(properties.getAlert().getCooldown()).isAfter(OffsetDateTime.now(zoneId)))
                .orElse(false);
    }

    private boolean isVoiceAllowedFor(ChatSubscription chat) {
        List<Long> allowedUserIds = properties.getAlert().getVoiceAllowedUserIds();
        if (allowedUserIds == null || allowedUserIds.isEmpty()) {
            return true;
        }
        Long userId = chat.userId();
        return userId != null && allowedUserIds.contains(userId);
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

    private static final class ManualRunState {
        volatile boolean blocking = true;
        volatile boolean timedOut = false;
        volatile boolean completed = false;
        volatile Future<?> future;
    }

    private static final class ScheduledAccountDispatch {
        private final ChatLinkedWbAccount account;
        private final List<Long> chatIds = new ArrayList<>();

        private ScheduledAccountDispatch(ChatLinkedWbAccount account) {
            this.account = account;
        }
    }
}
