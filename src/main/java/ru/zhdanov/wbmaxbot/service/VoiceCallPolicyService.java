package ru.zhdanov.wbmaxbot.service;

import org.springframework.stereotype.Service;
import ru.zhdanov.wbmaxbot.model.ChatSubscription;
import ru.zhdanov.wbmaxbot.repository.VoiceCallAttemptRepository;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Service
public class VoiceCallPolicyService {

    public static final int HARD_MAX_DAILY_CALLS_PER_ACCOUNT = 5;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final VoiceCallAttemptRepository voiceCallAttemptRepository;
    private final ZoneId zoneId;

    public VoiceCallPolicyService(VoiceCallAttemptRepository voiceCallAttemptRepository,
                                  ru.zhdanov.wbmaxbot.config.AppProperties properties) {
        this.voiceCallAttemptRepository = voiceCallAttemptRepository;
        this.zoneId = ZoneId.of(properties.getZoneId());
    }

    public AutoCallDecision evaluate(ChatSubscription chat, long accountId, OffsetDateTime now) {
        if (hasCustomWindow(chat) && !isWithinWindow(chat, now.toLocalTime())) {
            String window = formatWindow(chat.callTimeWindowStart(), chat.callTimeWindowEnd());
            return new AutoCallDecision(
                    false,
                    "outside_window",
                    "Сейчас вне окна автодозвона (" + window + "). Бот отправит тревогу и предложит ручной вызов."
            );
        }

        OffsetDateTime nextAllowedAfterAnswer = nextAllowedAfterAnswer(chat);
        if (nextAllowedAfterAnswer != null && nextAllowedAfterAnswer.isAfter(now)) {
            return new AutoCallDecision(
                    false,
                    "answer_cooldown",
                    "После успешного дозвона повторный автозвонок доступен не раньше "
                            + nextAllowedAfterAnswer.toLocalDate() + " " + nextAllowedAfterAnswer.toLocalTime().format(TIME_FORMATTER) + "."
            );
        }

        OffsetDateTime dayStart = now.toLocalDate().atStartOfDay(zoneId).toOffsetDateTime();
        OffsetDateTime nextDayStart = dayStart.plusDays(1);
        int chatAccountAttempts = voiceCallAttemptRepository.countStartedAutoAttemptsForChatAccount(
                chat.chatId(),
                accountId,
                dayStart,
                nextDayStart
        );
        int configuredLimit = resolveConfiguredDailyLimit(chat);
        if (chatAccountAttempts >= configuredLimit) {
            return new AutoCallDecision(
                    false,
                    "daily_limit_reached",
                    "Достигнут лимит автозвонков за сутки: " + configuredLimit + "."
            );
        }

        int accountAttempts = voiceCallAttemptRepository.countStartedAutoAttemptsForAccount(accountId, dayStart, nextDayStart);
        if (accountAttempts >= HARD_MAX_DAILY_CALLS_PER_ACCOUNT) {
            return new AutoCallDecision(
                    false,
                    "account_hard_limit_reached",
                    "Достигнут жёсткий лимит: не более " + HARD_MAX_DAILY_CALLS_PER_ACCOUNT + " автозвонков в сутки на один аккаунт."
            );
        }

        return new AutoCallDecision(true, "allowed", null);
    }

    public boolean hasCustomWindow(ChatSubscription chat) {
        return hasText(chat.callTimeWindowStart()) && hasText(chat.callTimeWindowEnd());
    }

    public OffsetDateTime nextAllowedAfterAnswer(ChatSubscription chat) {
        if (chat.lastCallAnsweredAt() == null) {
            return null;
        }
        int cooldownMinutes = Math.max(1, chat.callAnswerCooldownMinutes());
        return chat.lastCallAnsweredAt().plusMinutes(cooldownMinutes);
    }

    public int resolveConfiguredDailyLimit(ChatSubscription chat) {
        int configured = chat.callMaxDailyAttempts();
        if (configured <= 0) {
            configured = HARD_MAX_DAILY_CALLS_PER_ACCOUNT;
        }
        return Math.min(HARD_MAX_DAILY_CALLS_PER_ACCOUNT, configured);
    }

    public String normalizeWindowTime(String rawValue) {
        if (!hasText(rawValue)) {
            throw new IllegalArgumentException("Введите время в формате HH:mm, например 00:00.");
        }
        try {
            return LocalTime.parse(rawValue.trim(), TIME_FORMATTER).format(TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Введите время в формате HH:mm, например 00:00.");
        }
    }

    public boolean isWithinWindow(ChatSubscription chat, LocalTime currentTime) {
        if (!hasCustomWindow(chat)) {
            return true;
        }
        LocalTime start = parseTime(chat.callTimeWindowStart());
        LocalTime end = parseTime(chat.callTimeWindowEnd());
        if (start.equals(end)) {
            return true;
        }
        if (start.isBefore(end)) {
            return !currentTime.isBefore(start) && currentTime.isBefore(end);
        }
        return !currentTime.isBefore(start) || currentTime.isBefore(end);
    }

    public String formatWindow(String start, String end) {
        return hasText(start) && hasText(end) ? start + "-" + end : "круглосуточно";
    }

    private LocalTime parseTime(String value) {
        return LocalTime.parse(value, TIME_FORMATTER);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record AutoCallDecision(boolean allowed, String code, String reason) {
    }
}
