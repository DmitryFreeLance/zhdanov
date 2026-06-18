package ru.zhdanov.wbmaxbot.service;

import org.springframework.stereotype.Component;
import ru.zhdanov.wbmaxbot.model.AlertTrigger;
import ru.zhdanov.wbmaxbot.model.ReportRow;
import ru.zhdanov.wbmaxbot.model.ReportSummary;
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
        String header = buildReportHeader(result, accountLabel);

        if (result.rows().isEmpty()) {
            messages.add(buildEmptyReportMessage(result.summary(), header));
            return messages;
        }

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

    public String buildVoiceText(List<AlertTrigger> triggers) {
        if (triggers == null || triggers.isEmpty()) {
            return "Внимание. Обнаружена тревога WB Last Mile.";
        }
        if (triggers.size() == 1) {
            return buildVoiceText(triggers.getFirst());
        }

        StringBuilder text = new StringBuilder("Внимание. Обнаружено ")
                .append(triggers.size())
                .append(" тревоги WB Last Mile. ");
        for (int i = 0; i < triggers.size(); i++) {
            AlertTrigger trigger = triggers.get(i);
            ReportRow row = trigger.row();
            text.append("Пункт ")
                    .append(i + 1)
                    .append(". Маршрут ")
                    .append(row.route())
                    .append(". Парковка ")
                    .append(row.parking())
                    .append(". Количество ШК ")
                    .append(row.shk())
                    .append(". Норма выезда ")
                    .append(row.norm())
                    .append(". Заполнение ")
                    .append(Math.round(row.ratio() * 100))
                    .append(" процентов. ");
        }
        return text.toString().trim();
    }

    public String buildAlertSummaryMessage(List<AlertTrigger> triggers,
                                           boolean voiceCallEnabled,
                                           String accountLabel,
                                           String spokenText) {
        if (triggers == null || triggers.isEmpty()) {
            return "";
        }
        if (triggers.size() == 1) {
            String base = buildAlertMessage(triggers.getFirst(), voiceCallEnabled, accountLabel);
            return base + "\n\nТекст звонка:\n" + spokenText;
        }

        StringBuilder message = new StringBuilder("""
                Тревога WB Last Mile
                %s
                Причин в отчёте: %d
                Действие: %s

                """.formatted(
                formatAccountLine(accountLabel),
                triggers.size(),
                voiceCallEnabled ? "автоматический звонок запущен" : "нужно позвонить вручную"
        ));

        for (int i = 0; i < triggers.size(); i++) {
            AlertTrigger trigger = triggers.get(i);
            ReportRow row = trigger.row();
            message.append(i + 1)
                    .append(". ")
                    .append(resolveParkingEmoji(row.ratio()))
                    .append(" Парковка ")
                    .append(row.parking())
                    .append(" • ШК ")
                    .append(row.shk())
                    .append(" • Норма ")
                    .append(row.norm())
                    .append(" • Причина: ")
                    .append(trigger.reason())
                    .append("\n");
        }

        message.append("\nТекст звонка:\n").append(spokenText);
        return message.toString().trim();
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

    private String buildReportHeader(ScrapeResult result, String accountLabel) {
        StringBuilder header = new StringBuilder("Отчёт WB Last Mile\n");
        if (accountLabel != null && !accountLabel.isBlank()) {
            header.append("Аккаунт: ").append(accountLabel).append("\n");
        }
        header.append("Время: ")
                .append(DATE_TIME_FORMATTER.format(result.scrapedAt()))
                .append("\n\n");
        return header.toString();
    }

    private String buildEmptyReportMessage(ReportSummary summary, String header) {
        return (header
                + "WB вернул пустой отчёт: строк не найдено.\n"
                + "Строк: " + summary.rowsCount() + "\n"
                + "ШК всего: " + summary.totalShk() + "\n"
                + "Коробки всего: " + summary.totalBoxes() + "\n"
                + "КГТ всего: " + summary.totalKgt() + "\n"
                + "Объём, л: " + formatDecimal(summary.totalVolumeLiters())
                + (summary.heading() == null || summary.heading().isBlank() ? "" : "\nЗаголовок WB: " + summary.heading()))
                .trim();
    }

    private String formatDecimal(double value) {
        return String.format(Locale.US, "%.1f", value);
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
