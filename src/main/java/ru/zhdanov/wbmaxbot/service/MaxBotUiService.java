package ru.zhdanov.wbmaxbot.service;

import org.springframework.stereotype.Service;
import ru.zhdanov.wbmaxbot.config.AppProperties;
import ru.zhdanov.wbmaxbot.model.ChatSubscription;
import ru.zhdanov.wbmaxbot.model.ChatLinkedWbAccount;
import ru.zhdanov.wbmaxbot.model.MaxOutgoingMessage;

import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

@Service
public class MaxBotUiService {

    private final AppProperties properties;

    public MaxBotUiService(AppProperties properties) {
        this.properties = properties;
    }

    public MaxOutgoingMessage buildMainMenu(ChatSubscription chat) {
        return buildMainMenu(chat, 0);
    }

    public MaxOutgoingMessage buildMainMenu(ChatSubscription chat, int accountCount) {
        String text = """
                🤖 Панель WB Last Mile

                👤 WB аккаунтов: %s
                ⏱️ Автоотчёт: %s
                🚨 Порог ШК: %s
                📊 Порог заполнения: %s
                🅿️ Парковка тревоги: %s
                ☎️ Дозвон: %s
                📞 Телефон: %s

                Выберите действие ниже.
                """.formatted(
                accountCount,
                formatInterval(chat),
                formatShk(chat),
                formatRatio(chat),
                formatAlertParking(chat),
                chat.callEnabled() ? "включён" : "выключен",
                formatPhone(chat)
        ).trim();

        return withKeyboard(text,
                row(callback("📄 Отчёт сейчас", "report:now")),
                row(callback("👤 Аккаунты", "menu:accounts"), callback("⏱️ Интервал", "menu:interval")),
                row(callback("🚨 Тревога", "menu:alert"), callback("📞 Телефон", "menu:phone")),
                row(callback(toggleCallLabel(chat), "call:toggle")),
                row(callback("ℹ️ Статус", "menu:status"), callback("🔄 Обновить", "menu:main"))
        );
    }

    public MaxOutgoingMessage buildAccountsMenu(ChatSubscription chat, List<ChatLinkedWbAccount> accounts) {
        StringBuilder text = new StringBuilder("""
                👤 WB аккаунты

                Подключённые аккаунты:
                """);
        if (accounts.isEmpty()) {
            text.append("\nПока нет ни одного подключённого аккаунта.");
        } else {
            for (ChatLinkedWbAccount account : accounts) {
                text.append("\n")
                        .append(account.enabled() ? "✅ " : "⏸️ ")
                        .append(maskPhone(account.phoneNumber()))
                        .append(" • ")
                        .append(account.status() == null ? "CONNECTED" : account.status());
            }
        }

        List<List<Map<String, Object>>> rows = new ArrayList<>();
        String miniAppDeepLink = buildMiniAppDeepLink();
        if (miniAppDeepLink != null) {
            rows.add(row(callback("📎 Подключить по номеру", "wb:auth:start")));
            rows.add(row(link("🌐 Импорт через mini app", miniAppDeepLink)));
        } else {
            rows.add(row(callback("📎 Подключить по номеру", "wb:auth:start")));
        }
        for (ChatLinkedWbAccount account : accounts) {
            rows.add(row(
                    callback(account.enabled() ? "⏸️ Пауза " + shortPhone(account.phoneNumber()) : "✅ Сделать активным " + shortPhone(account.phoneNumber()),
                            "account:toggle:" + account.accountId()),
                    callback("🗑 Убрать", "account:unlink:" + account.accountId())
            ));
        }
        rows.add(row(callback("🔙 Назад", "menu:main")));
        return withKeyboard(text.toString().trim(), rows);
    }

    public MaxOutgoingMessage buildIntervalMenu(ChatSubscription chat) {
        String text = """
                ⏱️ Настройка автоотчёта

                Сейчас: %s

                Выберите интервал отправки отчёта.
                """.formatted(formatInterval(chat)).trim();

        return withKeyboard(text,
                row(callback("15 минут", "interval:15"), callback("30 минут", "interval:30")),
                row(callback("60 минут", "interval:60"), callback("❌ Выключить", "interval:off")),
                row(callback("🔙 Назад", "menu:main"))
        );
    }

    public MaxOutgoingMessage buildAlertMenu(ChatSubscription chat) {
        String text = """
                🚨 Настройка тревог

                Порог ШК: %s
                Порог заполнения: %s
                Парковка: %s

                Для отключения любого порога введите 0.
                """.formatted(formatShk(chat), formatRatio(chat), formatAlertParking(chat)).trim();

        return withKeyboard(text,
                row(callback("✏️ Изменить ШК", "input:shk"), callback("✏️ Изменить %", "input:ratio")),
                row(callback("🅿️ Выбрать парковку", "input:parking"), callback("🌐 Все парковки", "parking:clear")),
                row(callback("🔙 Назад", "menu:main"))
        );
    }

    public MaxOutgoingMessage buildPhoneMenu(ChatSubscription chat) {
        String text = """
                📞 Настройка дозвона

                Текущий номер: %s
                Режим дозвона: %s

                Можно ввести новый номер или очистить текущий.
                """.formatted(
                formatPhone(chat),
                chat.callEnabled() ? "включён" : "выключен"
        ).trim();
        String textWithDialHint = hasPhone(chat.phoneNumber())
                ? text + "\n\nДля звонка используйте номер: " + chat.phoneNumber()
                : text;

        List<List<Map<String, Object>>> rows = new ArrayList<>();
        rows.add(row(callback("✏️ Ввести номер", "input:phone"), callback("🗑 Очистить номер", "phone:clear")));
        rows.add(row(callback(toggleCallLabel(chat), "call:toggle")));
        rows.add(row(callback("🔙 Назад", "menu:main")));
        return withKeyboard(textWithDialHint, rows);
    }

    public MaxOutgoingMessage buildStatusMenu(ChatSubscription chat, boolean sessionExists, String mode, int activeChats) {
        String text = """
                ℹ️ Статус

                Режим: %s
                Сессия WB: %s
                Активных чатов: %d
                Автоотчёт: %s
                Порог ШК: %s
                Порог заполнения: %s
                Парковка тревоги: %s
                Дозвон: %s
                Телефон: %s
                """.formatted(
                mode,
                sessionExists ? "готова" : "не найдена",
                activeChats,
                formatInterval(chat),
                formatShk(chat),
                formatRatio(chat),
                formatAlertParking(chat),
                chat.callEnabled() ? "включён" : "выключен",
                formatPhone(chat)
        ).trim();

        return withKeyboard(text,
                row(callback("🔙 Назад", "menu:main"))
        );
    }

    public MaxOutgoingMessage buildPhonePrompt() {
        return withKeyboard("""
                📞 Введите номер телефона для дозвона.

                Примеры:
                +79991234567
                89991234567

                Для отмены нажмите кнопку ниже.
                """.trim(),
                row(callback("🔙 Назад", "menu:phone"))
        );
    }

    public MaxOutgoingMessage buildShkPrompt() {
        return withKeyboard("""
                🚨 Введите порог по ШК целым числом.

                Примеры:
                1200
                1500

                Введите 0, чтобы отключить этот порог.
                """.trim(),
                row(callback("🔙 Назад", "menu:alert"))
        );
    }

    public MaxOutgoingMessage buildRatioPrompt() {
        return withKeyboard("""
                📊 Введите порог заполнения в процентах.

                Примеры:
                80
                90

                Введите 0, чтобы отключить этот порог.
                """.trim(),
                row(callback("🔙 Назад", "menu:alert"))
        );
    }

    public MaxOutgoingMessage buildAlertParkingPrompt() {
        return withKeyboard("""
                🅿️ Введите название парковки для тревоги.

                Бот будет присылать тревогу только по этой парковке.
                Название должно совпадать с тем, как парковка называется в отчёте.

                Пример:
                Парковка 1

                Чтобы вернуть тревоги по всем парковкам, нажмите кнопку ниже.
                """.trim(),
                row(callback("🌐 Все парковки", "parking:clear")),
                row(callback("🔙 Назад", "menu:alert"))
        );
    }

    public String buildUnknownInputMessage() {
        return "Не понял ввод. Используйте кнопки или отправьте ожидаемое значение.";
    }

    public String buildPhoneSavedMessage(String phoneNumber) {
        return "Номер сохранён: " + phoneNumber;
    }

    public String buildPhoneClearedMessage() {
        return "Номер телефона очищен.";
    }

    public String buildCallToggleBlockedMessage() {
        return "Сначала сохраните номер телефона для дозвона.";
    }

    public String buildCallToggleMessage(boolean enabled) {
        return enabled ? "Режим дозвона включён." : "Режим дозвона выключен.";
    }

    public String buildShkSavedMessage(Integer threshold) {
        return threshold == null ? "Порог ШК отключён." : "Порог ШК сохранён: " + threshold;
    }

    public String buildRatioSavedMessage(Double threshold) {
        return threshold == null
                ? "Порог заполнения отключён."
                : "Порог заполнения сохранён: " + formatRatioPercent(threshold);
    }

    public String buildAlertParkingSavedMessage(String parking) {
        return parking == null || parking.isBlank()
                ? "Тревога снова настроена на все парковки."
                : "Тревога настроена на парковку: " + parking;
    }

    public String buildIntervalSavedMessage(boolean enabled, int intervalMinutes) {
        return enabled
                ? "Автоотчёт настроен: каждые " + intervalMinutes + " минут."
                : "Автоотчёт выключен.";
    }

    public String buildAccountToggleMessage(boolean enabled, String phoneNumber) {
        return (enabled ? "Аккаунт сделан активным: " : "Аккаунт поставлен на паузу: ") + maskPhone(phoneNumber);
    }

    public String buildAccountUnlinkedMessage(String phoneNumber) {
        return "Аккаунт отключён от этого чата: " + maskPhone(phoneNumber);
    }

    public MaxOutgoingMessage buildWbAuthPhonePrompt() {
        return withKeyboard("""
                📎 Подключение WB по файлу

                Отправьте номер телефона аккаунта WB.
                Бот попробует найти готовый файл сессии для этого номера и сразу подключить аккаунт.

                Примеры:
                +79991234567
                89991234567

                Для отмены нажмите кнопку ниже.
                """.trim(),
                row(callback("❌ Отменить", "wb:auth:cancel"))
        );
    }

    public MaxOutgoingMessage buildWbAuthCodePrompt(String phoneNumber) {
        return withKeyboard("""
                🔐 Код WB отправлен

                Номер: %s

                Теперь отправьте код из SMS одним сообщением.
                Для отмены нажмите кнопку ниже.
                """.formatted(maskPhone(phoneNumber)).trim(),
                row(callback("🔁 Отправить код ещё раз", "wb:auth:resend")),
                row(callback("❌ Отменить", "wb:auth:cancel"))
        );
    }

    public String buildWbAuthCancelledMessage() {
        return "Авторизация WB отменена.";
    }

    public String buildWbAuthFileMissingMessage(String phoneNumber, java.nio.file.Path expectedPath) {
        return """
                Не нашёл файл WB-сессии для номера %s.

                Положите файл сюда:
                %s

                Имя файла лучше сделать по номеру, например:
                %s
                """.formatted(
                maskPhone(phoneNumber),
                expectedPath.getParent(),
                expectedPath.getFileName()
        ).trim();
    }

    public String buildWbAuthStartedMessage(String phoneNumber) {
        return "Запросил код WB для номера " + maskPhone(phoneNumber) + ".";
    }

    public String buildWbAuthStartingMessage(String phoneNumber) {
        return "Запускаю вход в WB для " + maskPhone(phoneNumber) + ". Это может занять до минуты.";
    }

    public String buildWbAuthStillStartingMessage() {
        return "Ещё запрашиваю код WB. Подождите немного или нажмите Отменить.";
    }

    public String buildWbAuthResentMessage() {
        return "Попросил WB отправить код ещё раз.";
    }

    public String buildWbAuthSuccessMessage(String phoneNumber) {
        return "WB аккаунт подключён и сделан активным: " + maskPhone(phoneNumber);
    }

    public MaxOutgoingMessage buildMenuMessage(String text) {
        return withKeyboard(text, row(callback("🔙 В меню", "menu:main")));
    }

    public MaxOutgoingMessage buildErrorMessage(String text) {
        return buildMenuMessage(compactErrorText(text));
    }

    public MaxOutgoingMessage buildReportStartingMessage() {
        return new MaxOutgoingMessage("Формирую отчёт...");
    }

    public MaxOutgoingMessage buildReportAlreadyRunningMessage() {
        return buildMenuMessage("Отчёт уже формируется. Подождите, пока придёт результат.");
    }

    public MaxOutgoingMessage buildReportTimedOutMessage() {
        return buildErrorMessage("Не удалось сформировать отчёт. Попробуйте ещё раз.");
    }

    public MaxOutgoingMessage buildAlertMessage(String text, String phoneNumber, boolean voiceCallEnabled) {
        String finalText = text;
        if (hasPhone(phoneNumber)) {
            String suffix = voiceCallEnabled
                    ? "\n\n📞 Если нужно перезвонить вручную: " + phoneNumber
                    : "\n\n📞 Номер для ручного звонка: " + phoneNumber;
            finalText += suffix;
        }
        return buildMenuMessage(finalText);
    }

    private String formatInterval(ChatSubscription chat) {
        return chat.autoReportEnabled() ? "каждые " + chat.reportIntervalMinutes() + " минут" : "выключен";
    }

    private String formatShk(ChatSubscription chat) {
        return chat.shkThreshold() == null ? "выключен" : String.valueOf(chat.shkThreshold());
    }

    private String formatRatio(ChatSubscription chat) {
        return chat.ratioThreshold() == null ? "выключен" : formatRatioPercent(chat.ratioThreshold());
    }

    private String formatAlertParking(ChatSubscription chat) {
        return chat.alertParking() == null || chat.alertParking().isBlank() ? "все парковки" : chat.alertParking();
    }

    private String compactErrorText(String text) {
        if (text == null || text.isBlank()) {
            return "Произошла ошибка. Попробуйте ещё раз.";
        }

        if (text.startsWith("Timed out waiting for WB report table")
                || text.startsWith("WB report page did not open in time")) {
            return "WB долго открывает страницу отчёта. Попробуйте ещё раз чуть позже.";
        }

        if (text.startsWith("Не удалось получить отчёт для аккаунта ")) {
            int detailsIndex = text.indexOf(": ");
            if (detailsIndex > 0) {
                String prefix = text.substring(0, detailsIndex);
                String details = text.substring(detailsIndex + 2);
                if (details.startsWith("Timed out waiting for WB report table")
                        || details.startsWith("WB report page did not open in time")) {
                    return prefix + ": WB долго открывает страницу отчёта. Попробуйте ещё раз чуть позже.";
                }
            }
        }

        int cutIndex = indexOfFirst(text, ". URL:", ". Title:", ". Heading:", ". Screen:", "\nCall log:");
        String compact = cutIndex >= 0 ? text.substring(0, cutIndex).trim() : text.trim();
        return compact.length() > 500 ? compact.substring(0, 500).trim() + "..." : compact;
    }

    private int indexOfFirst(String text, String... needles) {
        int result = -1;
        for (String needle : needles) {
            int index = text.indexOf(needle);
            if (index >= 0 && (result < 0 || index < result)) {
                result = index;
            }
        }
        return result;
    }

    private String formatRatioPercent(double threshold) {
        return String.format(Locale.US, "%.0f%%", threshold * 100.0d);
    }

    private String formatPhone(ChatSubscription chat) {
        return chat.phoneNumber() == null || chat.phoneNumber().isBlank() ? "не указан" : chat.phoneNumber();
    }

    private String toggleCallLabel(ChatSubscription chat) {
        return chat.callEnabled() ? "☎️ Выключить дозвон" : "☎️ Включить дозвон";
    }

    @SafeVarargs
    private final MaxOutgoingMessage withKeyboard(String text, List<Map<String, Object>>... rows) {
        return withKeyboard(text, Arrays.asList(rows));
    }

    private MaxOutgoingMessage withKeyboard(String text, List<List<Map<String, Object>>> rows) {
        return new MaxOutgoingMessage(text, true, List.of(Map.of(
                "type", "inline_keyboard",
                "payload", Map.of("buttons", rows)
        )));
    }

    private List<Map<String, Object>> row(Map<String, Object>... buttons) {
        return List.of(buttons);
    }

    private Map<String, Object> callback(String text, String payload) {
        return Map.of(
                "type", "callback",
                "text", text,
                "payload", payload
        );
    }

    private Map<String, Object> link(String text, String url) {
        return Map.of(
                "type", "link",
                "text", text,
                "url", url
        );
    }

    private String buildMiniAppDeepLink() {
        String botUsername = properties.getMax().getBotUsername();
        if (botUsername == null || botUsername.isBlank()) {
            return null;
        }
        return "https://max.ru/" + botUsername + "?startapp=wb_auth";
    }

    private String shortPhone(String phoneNumber) {
        String masked = maskPhone(phoneNumber);
        return masked.length() > 8 ? masked.substring(masked.length() - 8) : masked;
    }

    private String maskPhone(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return "без номера";
        }
        String digits = phoneNumber.replaceAll("[^0-9]", "");
        if (digits.length() < 4) {
            return phoneNumber;
        }
        return "+" + digits.charAt(0) + "***" + digits.substring(digits.length() - 4);
    }

    private boolean hasPhone(String phoneNumber) {
        return phoneNumber != null && !phoneNumber.isBlank();
    }

}
