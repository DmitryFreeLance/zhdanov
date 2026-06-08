package ru.zhdanov.wbmaxbot.service;

import org.springframework.stereotype.Component;
import ru.zhdanov.wbmaxbot.model.AlertTrigger;
import ru.zhdanov.wbmaxbot.model.ReportRow;
import ru.zhdanov.wbmaxbot.model.ScrapeResult;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class NotificationFormatter {

    private static final int MAX_MESSAGE_LENGTH = 3900;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public List<String> buildReportMessages(ScrapeResult result, int maxRowsInMessage) {
        return buildReportMessages(result, maxRowsInMessage, null);
    }

    public List<String> buildReportMessages(ScrapeResult result, int maxRowsInMessage, String accountLabel) {
        List<String> messages = new ArrayList<>();
        String header = """
                Отчёт WB Last Mile
                %s
                Время: %s
                                
                """.formatted(
                formatAccountLine(accountLabel),
                DATE_TIME_FORMATTER.format(result.scrapedAt())
        );

        StringBuilder current = new StringBuilder(header);
        int rowsAdded = 0;

        for (ReportRow row : result.rows()) {
            String block = formatRowBlock(row);
            boolean mustSplit = current.length() + block.length() > MAX_MESSAGE_LENGTH || rowsAdded >= maxRowsInMessage;
            if (mustSplit) {
                messages.add(current.toString().trim());
                current = new StringBuilder("Продолжение отчёта WB Last Mile\n\n");
                rowsAdded = 0;
            }
            current.append(block);
            rowsAdded++;
        }

        if (!current.isEmpty()) {
            messages.add(current.toString().trim());
        }
        return messages;
    }

    public String buildAlertMessage(AlertTrigger trigger, boolean voiceCallEnabled) {
        return buildAlertMessage(trigger, voiceCallEnabled, null);
    }

    public String buildAlertMessage(AlertTrigger trigger, boolean voiceCallEnabled, String accountLabel) {
        ReportRow row = trigger.row();
        return """
                Тревога WB Last Mile
                %s
                Причина: %s
                Действие: %s
                                
                %s Парковка %s
                Коробки: %d
                КГТ: %d
                ШК: %d
                Норма: %d
                """.formatted(
                formatAccountLine(accountLabel),
                trigger.reason(),
                voiceCallEnabled ? "автоматический звонок запущен" : "нужно позвонить вручную",
                resolveParkingEmoji(row.ratio()),
                row.parking(),
                row.boxes(),
                row.kgt(),
                row.shk(),
                row.norm()
        ).trim();
    }

    public String buildVoiceText(AlertTrigger trigger) {
        ReportRow row = trigger.row();
        return "Внимание. " +
                "Маршрут " + row.route() + ". " +
                "Парковка " + row.parking() + ". " +
                "Количество ШК " + row.shk() + ". " +
                "Норма выезда " + row.norm() + ". " +
                "Заполнение " + Math.round(row.ratio() * 100) + " процентов.";
    }

    public String buildWelcomeMessage() {
        return """
                Бот подключён.
                                
                Команды:
                /report - отправить отчёт сейчас
                /status - статус сервиса
                /unsubscribe - отключить сообщения для этого чата
                /help - показать команды
                                
                Важно:
                бот шлёт периодические отчёты в этот чат,
                а при срабатывании порога по ШК дополнительно отправляет тревогу с напоминанием, что нужно позвонить.
                """.trim();
    }

    public String buildStatusMessage(boolean sessionExists, int activeChats, String mode, boolean voiceCallEnabled) {
        return """
                Статус сервиса
                Режим: %s
                Сессии WB: %s
                Активных чатов: %d
                Автозвонок: %s
                """.formatted(
                mode,
                sessionExists ? "готов" : "не найден",
                activeChats,
                voiceCallEnabled ? "включен" : "выключен, только напоминание"
        ).trim();
    }

    private String formatRowBlock(ReportRow row) {
        return """
                %s Парковка %s
                Коробки: %d
                КГТ: %d
                ШК: %d
                Норма: %d
                                
                """.formatted(
                resolveParkingEmoji(row.ratio()),
                row.parking(),
                row.boxes(),
                row.kgt(),
                row.shk(),
                row.norm()
        );
    }

    private String formatPercent(double ratio) {
        return String.format(Locale.US, "%.1f%%", ratio * 100.0d);
    }

    private String formatAccountLine(String accountLabel) {
        return accountLabel == null || accountLabel.isBlank() ? "" : "Аккаунт: " + accountLabel;
    }

    private String resolveParkingEmoji(double ratio) {
        if (ratio >= 0.8d) {
            return "🔴";
        }
        if (ratio >= 0.7d) {
            return "🟠";
        }
        if (ratio >= 0.6d) {
            return "🟡";
        }
        return "🟢";
    }
}
