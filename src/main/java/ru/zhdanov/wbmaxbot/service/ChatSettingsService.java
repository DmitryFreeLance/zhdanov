package ru.zhdanov.wbmaxbot.service;

import org.springframework.stereotype.Service;
import ru.zhdanov.wbmaxbot.model.ChatSubscription;
import ru.zhdanov.wbmaxbot.repository.SubscriptionRepository;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@Service
public class ChatSettingsService {

    public static final String PENDING_PHONE = "phone";
    public static final String PENDING_SHK_THRESHOLD = "shk-threshold";
    public static final String PENDING_RATIO_THRESHOLD = "ratio-threshold";
    public static final String PENDING_ALERT_PARKING = "alert-parking";
    public static final String PENDING_WB_AUTH_PHONE = "wb-auth-phone";
    public static final String PENDING_WB_AUTH_STARTING = "wb-auth-starting";
    public static final String PENDING_WB_AUTH_CODE = "wb-auth-code";

    private final SubscriptionRepository subscriptionRepository;
    private final ZoneId zoneId;

    public ChatSettingsService(SubscriptionRepository subscriptionRepository,
                               ru.zhdanov.wbmaxbot.config.AppProperties properties) {
        this.subscriptionRepository = subscriptionRepository;
        this.zoneId = ZoneId.of(properties.getZoneId());
    }

    public Optional<ChatSubscription> findByChatId(long chatId) {
        return subscriptionRepository.findByChatId(chatId);
    }

    public ChatSubscription getRequired(long chatId) {
        return findByChatId(chatId)
                .orElseThrow(() -> new IllegalStateException("Чат " + chatId + " не найден. Сначала нажмите /start или Начать."));
    }

    public List<ChatSubscription> activeChats() {
        return subscriptionRepository.findActive();
    }

    public List<ChatSubscription> findChatsDueForAutoReport(OffsetDateTime now) {
        return activeChats().stream()
                .filter(ChatSubscription::autoReportEnabled)
                .filter(chat -> isDue(chat, now))
                .toList();
    }

    public void setReportInterval(long chatId, int intervalMinutes) {
        subscriptionRepository.updateReportSchedule(chatId, true, intervalMinutes);
    }

    public void disableAutoReport(long chatId) {
        subscriptionRepository.updateReportSchedule(chatId, false, 15);
    }

    public void markReportSent(long chatId, OffsetDateTime sentAt) {
        subscriptionRepository.markReportSent(chatId, sentAt);
    }

    public void setShkThreshold(long chatId, Integer threshold) {
        subscriptionRepository.updateShkThreshold(chatId, threshold);
    }

    public void setRatioThreshold(long chatId, Double threshold) {
        subscriptionRepository.updateRatioThreshold(chatId, threshold);
    }

    public void setAlertParking(long chatId, String alertParking) {
        subscriptionRepository.updateAlertParking(chatId, alertParking);
    }

    public void setCallEnabled(long chatId, boolean enabled) {
        subscriptionRepository.updateCallEnabled(chatId, enabled);
    }

    public void setPhoneNumber(long chatId, String phoneNumber) {
        subscriptionRepository.updatePhoneNumber(chatId, phoneNumber);
    }

    public void setPendingInputState(long chatId, String state) {
        subscriptionRepository.updatePendingInputState(chatId, state);
    }

    public void clearPendingInputState(long chatId) {
        subscriptionRepository.updatePendingInputState(chatId, null);
    }

    public void startPendingWbAuthPhone(long chatId) {
        subscriptionRepository.updatePendingWbAuth(chatId, PENDING_WB_AUTH_PHONE, null, null);
    }

    public void startPendingWbAuthStarting(long chatId, String phoneNumber) {
        subscriptionRepository.updatePendingWbAuth(chatId, PENDING_WB_AUTH_STARTING, null, phoneNumber);
    }

    public void startPendingWbAuthCode(long chatId, String flowId, String phoneNumber) {
        subscriptionRepository.updatePendingWbAuth(chatId, PENDING_WB_AUTH_CODE, flowId, phoneNumber);
    }

    public void clearPendingWbAuth(long chatId) {
        subscriptionRepository.updatePendingWbAuth(chatId, null, null, null);
    }

    public OffsetDateTime now() {
        return OffsetDateTime.now(zoneId);
    }

    private boolean isDue(ChatSubscription chat, OffsetDateTime now) {
        OffsetDateTime lastSentAt = chat.lastReportSentAt();
        if (lastSentAt == null) {
            return true;
        }
        return !lastSentAt.plusMinutes(chat.reportIntervalMinutes()).isAfter(now);
    }
}
