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
        List<String> messages = new ArrayList<>();
        String header = """
                Отчёт WB Last Mile
                Время: %s
                Строк: %d
                ШК всего: %d
                Коробки всего: %d
                КГТ всего: %d
                Объём: %.2f л
                                
                """.formatted(
                DATE_TIME_FORMATTER.format(result.scrapedAt()),
                result.summary().rowsCount(),
                result.summary().totalShk(),
                result.summary().totalBoxes(),
                result.summary().totalKgt(),
                result.summary().totalVolumeLiters()
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

    public String buildAlertMessage(AlertTrigger trigger) {
        ReportRow row = trigger.row();
        return """
                Тревога WB Last Mile
                Причина: %s
                                
                ЛО: %s
                Маршрут: %s
                Парковка: %s
                Коробки: %d
                КГТ: %d
                ШК: %d
                Норма: %d
                Заполнение: %s
                """.formatted(
                trigger.reason(),
                row.loName(),
                row.route(),
                row.parking(),
                row.boxes(),
                row.kgt(),
                row.shk(),
                row.norm(),
                formatPercent(row.ratio())
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
                а при срабатывании порога по ШК дополнительно отправляет тревогу и запускает звонок.
                """.trim();
    }

    public String buildStatusMessage(boolean sessionExists, int activeChats, String mode) {
        return """
                Статус сервиса
                Режим: %s
                Файл сессии WB: %s
                Активных чатов: %d
                """.formatted(
                mode,
                sessionExists ? "готов" : "не найден",
                activeChats
        ).trim();
    }

    private String formatRowBlock(ReportRow row) {
        return """
                ЛО: %s
                Маршрут: %s
                Парковка: %s
                Коробки: %d
                КГТ: %d
                ШК: %d
                Норма: %d
                Заполнение: %s
                                
                """.formatted(
                row.loName(),
                row.route(),
                row.parking(),
                row.boxes(),
                row.kgt(),
                row.shk(),
                row.norm(),
                formatPercent(row.ratio())
        );
    }

    private String formatPercent(double ratio) {
        return String.format(Locale.US, "%.1f%%", ratio * 100.0d);
    }
}
