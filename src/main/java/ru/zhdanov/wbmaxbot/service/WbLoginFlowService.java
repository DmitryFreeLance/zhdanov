package ru.zhdanov.wbmaxbot.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
import com.microsoft.playwright.options.ViewportSize;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.zhdanov.wbmaxbot.config.AppProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class WbLoginFlowService {

    private static final Logger log = LoggerFactory.getLogger(WbLoginFlowService.class);
    private static final String LOGIN_URL = "https://logistics.wildberries.ru/auth/login";
    private static final Duration FLOW_TTL = Duration.ofMinutes(10);
    private static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private final AppProperties properties;
    private final ZoneId zoneId;
    private final Map<String, PendingFlow> pendingFlows = new ConcurrentHashMap<>();
    private final ExecutorService authExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "wb-auth-flow");
        thread.setDaemon(true);
        return thread;
    });

    public WbLoginFlowService(AppProperties properties) {
        this.properties = properties;
        this.zoneId = ZoneId.of(properties.getZoneId());
    }

    public CompletableFuture<StartedAuth> startAsync(String phoneNumber) {
        return CompletableFuture.supplyAsync(() -> start(phoneNumber), authExecutor);
    }

    public StartedAuth start(String phoneNumber) {
        String normalizedPhone = normalizePhone(phoneNumber);
        log.info("Starting WB auth flow for {}", maskPhone(normalizedPhone));

        try {
            Playwright playwright = Playwright.create();
            Browser browser = playwright.chromium().launch(defaultLaunchOptions(properties.getWildberries().isHeadless()));
            BrowserContext context = browser.newContext(defaultContextOptions());
            context.setExtraHTTPHeaders(Map.of(
                    "Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Upgrade-Insecure-Requests", "1"
            ));
            context.addInitScript(buildStealthInitScript());
            Page page = context.newPage();
            page.navigate(LOGIN_URL,
                    new Page.NavigateOptions()
                            .setTimeout(timeoutMs())
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            log.info("WB auth page opened for {}", maskPhone(normalizedPhone));

            waitForLoginUi(page, properties.getWildberries().getTimeout());
            fillPhoneAndRequestCode(page, normalizedPhone);
            log.info("WB phone submitted for {}", maskPhone(normalizedPhone));
            waitForCodeUi(page, properties.getWildberries().getTimeout());
            String codeScreenHint = extractCodeScreenHint(page);
            log.info("WB code input detected for {}. Hint: {}", maskPhone(normalizedPhone), codeScreenHint);

            String flowId = UUID.randomUUID().toString();
            pendingFlows.put(flowId, new PendingFlow(playwright, browser, context, page, normalizedPhone, OffsetDateTime.now(zoneId).plus(FLOW_TTL)));
            return new StartedAuth(flowId, normalizedPhone, codeScreenHint);
        } catch (Exception e) {
            log.warn("WB auth start failed for {}: {}", maskPhone(normalizedPhone), e.getMessage());
            throw new IllegalStateException("Не удалось начать авторизацию WB: " + e.getMessage(), e);
        }
    }

    public String confirm(String flowId, String code) {
        PendingFlow flow = getRequired(flowId);
        Path tempFile = null;
        try {
            submitCode(flow.page(), code);
            waitForAuthorizedPage(flow.page(), properties.getWildberries().getBootstrapTimeout());
            tempFile = Files.createTempFile("wb-auth-storage-", ".json");
            flow.context().storageState(new BrowserContext.StorageStateOptions().setPath(tempFile));
            return Files.readString(tempFile);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось подтвердить код WB: " + e.getMessage(), e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                }
            }
            if (flowId != null && pendingFlows.containsKey(flowId)) {
                closeFlow(flowId);
            }
        }
    }

    public void cancel(String flowId) {
        if (flowId == null || flowId.isBlank()) {
            return;
        }
        closeFlow(flowId);
    }

    public void resendCode(String flowId) {
        PendingFlow flow = getRequired(flowId);
        clickFirst(flow.page(),
                "button:has-text('Отправить ещё раз')",
                "button:has-text('Отправить еще раз')",
                "button:has-text('Отправить повторно')",
                "button:has-text('Запросить код повторно')",
                "button:has-text('Получить код повторно')",
                "button:has-text('Выслать код повторно')",
                "a:has-text('Отправить ещё раз')",
                "a:has-text('Отправить еще раз')",
                "a:has-text('Отправить повторно')"
        );
        log.info("WB resend code clicked for {}", maskPhone(flow.phoneNumber()));
        waitForCodeUi(flow.page(), properties.getWildberries().getTimeout());
    }

    @PreDestroy
    public void shutdownExecutor() {
        authExecutor.shutdownNow();
    }

    @Scheduled(fixedDelay = 60000)
    public void cleanupExpired() {
        OffsetDateTime now = OffsetDateTime.now(zoneId);
        pendingFlows.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().expiresAt().isBefore(now);
            if (expired) {
                safelyClose(entry.getValue());
            }
            return expired;
        });
    }

    private PendingFlow getRequired(String flowId) {
        PendingFlow flow = pendingFlows.get(flowId);
        if (flow == null || flow.expiresAt().isBefore(OffsetDateTime.now(zoneId))) {
            if (flow != null) {
                closeFlow(flowId);
            }
            throw new IllegalArgumentException("Сессия авторизации истекла. Начните вход заново.");
        }
        return flow;
    }

    private void closeFlow(String flowId) {
        PendingFlow flow = pendingFlows.remove(flowId);
        if (flow != null) {
            safelyClose(flow);
        }
    }

    private void safelyClose(PendingFlow flow) {
        try {
            flow.context().close();
        } catch (Exception ignored) {
        }
        try {
            flow.browser().close();
        } catch (Exception ignored) {
        }
        try {
            flow.playwright().close();
        } catch (Exception ignored) {
        }
    }

    private BrowserType.LaunchOptions defaultLaunchOptions(boolean headless) {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(headless)
                .setArgs(List.of(
                        "--disable-blink-features=AutomationControlled",
                        "--disable-dev-shm-usage",
                        "--no-sandbox",
                        "--disable-features=IsolateOrigins,site-per-process",
                        "--window-size=1440,900",
                        "--lang=ru-RU",
                        "--start-maximized"
                ));
        String browserExecutablePath = properties.getWildberries().getBrowserExecutablePath();
        if (browserExecutablePath != null && !browserExecutablePath.isBlank()) {
            options.setExecutablePath(Path.of(browserExecutablePath));
        }
        return options;
    }

    private Browser.NewContextOptions defaultContextOptions() {
        Browser.NewContextOptions options = new Browser.NewContextOptions()
                .setLocale("ru-RU")
                .setTimezoneId(zoneId.getId())
                .setUserAgent(DESKTOP_USER_AGENT)
                .setViewportSize(new ViewportSize(1440, 900))
                .setScreenSize(1440, 900)
                .setDeviceScaleFactor(1)
                .setIsMobile(false)
                .setHasTouch(false);
        return options;
    }

    private void waitForLoginUi(Page page, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (count(page, "input[type='tel'], input[inputmode='tel'], input[name*='phone' i], input[autocomplete='tel'], input[placeholder*='тел' i]") > 0) {
                return;
            }
            page.waitForTimeout(1000);
        }
        throw new IllegalStateException("Не удалось дождаться формы входа WB");
    }

    private void fillPhoneAndRequestCode(Page page, String phoneNumber) {
        Locator phoneInput = first(page, "input[type='tel'], input[inputmode='tel'], input[name*='phone' i], input[autocomplete='tel'], input[placeholder*='тел' i]");
        phoneInput.click();
        phoneInput.fill("");
        phoneInput.type(phoneNumber, new Locator.TypeOptions().setDelay(80));
        page.waitForTimeout(300);
        clickFirst(page,
                "button:has-text('Получить код')",
                "button:has-text('Продолжить')",
                "button:has-text('Далее')",
                "button:has-text('Войти')",
                "input[type='submit']"
        );
    }

    private void waitForCodeUi(Page page, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (hasCodeInput(page)) {
                return;
            }
            page.waitForTimeout(1000);
        }
        throw new IllegalStateException("Не удалось дождаться поля ввода кода WB. URL: " + page.url() + ". Экран: " + extractCodeScreenHint(page));
    }

    private void submitCode(Page page, String code) {
        String normalizedCode = code == null ? "" : code.replaceAll("\\s+", "");
        if (normalizedCode.isBlank()) {
            throw new IllegalArgumentException("Код не должен быть пустым");
        }

        if (count(page, "input[autocomplete='one-time-code'], input[name*='code' i], input[placeholder*='код' i]") > 0) {
            first(page, "input[autocomplete='one-time-code'], input[name*='code' i], input[placeholder*='код' i]").fill(normalizedCode);
        } else {
            var inputs = page.locator("input[inputmode='numeric'], input[type='number']");
            int count = inputs.count();
            if (count <= 0) {
                throw new IllegalStateException("Поля кода WB не найдены");
            }
            for (int i = 0; i < Math.min(count, normalizedCode.length()); i++) {
                inputs.nth(i).fill(String.valueOf(normalizedCode.charAt(i)));
            }
        }

        clickFirst(page,
                "button:has-text('Подтвердить')",
                "button:has-text('Продолжить')",
                "button:has-text('Войти')",
                "button:has-text('Далее')",
                "input[type='submit']"
        );
    }

    private void waitForAuthorizedPage(Page page, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (page.url().contains("/reports/remainders/last-mile/") && count(page, "table") > 0) {
                return;
            }
            if (page.url().contains("/auth/login")) {
                page.waitForTimeout(1000);
                continue;
            }
            page.waitForTimeout(1000);
        }
        throw new IllegalStateException("WB не подтвердил вход. Возможно, код неверный или включена дополнительная защита.");
    }

    private boolean hasCodeInput(Page page) {
        return count(page, "input[autocomplete='one-time-code'], input[name*='code' i], input[placeholder*='код' i], input[inputmode='numeric'], input[type='number']") > 0;
    }

    private int count(Page page, String selector) {
        return page.locator(selector).count();
    }

    private com.microsoft.playwright.Locator first(Page page, String selector) {
        if (count(page, selector) <= 0) {
            throw new IllegalStateException("Не найден элемент WB: " + selector);
        }
        return page.locator(selector).first();
    }

    private void clickFirst(Page page, String... selectors) {
        for (String selector : selectors) {
            if (count(page, selector) > 0) {
                page.locator(selector).first().click();
                return;
            }
        }
        throw new IllegalStateException("Не найдена кнопка продолжения WB");
    }

    private long timeoutMs() {
        return properties.getWildberries().getTimeout().toMillis();
    }

    private String normalizePhone(String phoneNumber) {
        String digits = phoneNumber == null ? "" : phoneNumber.replaceAll("[^0-9]", "");
        if (digits.length() == 10) {
            return "+7" + digits;
        }
        if (digits.length() == 11 && digits.startsWith("8")) {
            return "+7" + digits.substring(1);
        }
        if (digits.length() == 11 && digits.startsWith("7")) {
            return "+" + digits;
        }
        throw new IllegalArgumentException("Введите номер WB в формате +79991234567");
    }

    private String maskPhone(String phoneNumber) {
        String digits = phoneNumber == null ? "" : phoneNumber.replaceAll("[^0-9]", "");
        if (digits.length() < 4) {
            return "unknown";
        }
        return "+" + digits.charAt(0) + "***" + digits.substring(digits.length() - 4);
    }

    private String extractCodeScreenHint(Page page) {
        try {
            String bodyText = page.locator("body").innerText();
            if (bodyText == null || bodyText.isBlank()) {
                return "WB открыл экран ввода кода.";
            }

            String normalized = bodyText
                    .replace('\u00A0', ' ')
                    .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                    .replaceAll("\\n{2,}", "\n")
                    .trim();

            String[] lines = normalized.split("\\n");
            StringBuilder selected = new StringBuilder();
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isBlank()) {
                    continue;
                }
                String lower = trimmed.toLowerCase();
                if (lower.contains("код")
                        || lower.contains("sms")
                        || lower.contains("смс")
                        || lower.contains("отправ")
                        || lower.contains("повтор")
                        || lower.contains("подтверд")
                        || lower.contains("номер")) {
                    if (!selected.isEmpty()) {
                        selected.append(" | ");
                    }
                    selected.append(trimmed);
                    if (selected.length() >= 220) {
                        break;
                    }
                }
            }

            String result = selected.isEmpty() ? normalized : selected.toString();
            if (result.length() > 240) {
                result = result.substring(0, 240) + "...";
            }
            return result;
        } catch (Exception e) {
            return "WB открыл экран ввода кода.";
        }
    }

    private String buildStealthInitScript() {
        return """
                Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                Object.defineProperty(navigator, 'platform', { get: () => 'Win32' });
                Object.defineProperty(navigator, 'language', { get: () => 'ru-RU' });
                Object.defineProperty(navigator, 'languages', { get: () => ['ru-RU', 'ru', 'en-US', 'en'] });
                Object.defineProperty(navigator, 'hardwareConcurrency', { get: () => 8 });
                Object.defineProperty(navigator, 'deviceMemory', { get: () => 8 });
                Object.defineProperty(navigator, 'plugins', {
                  get: () => [
                    { name: 'Chrome PDF Plugin' },
                    { name: 'Chrome PDF Viewer' },
                    { name: 'Native Client' }
                  ]
                });
                window.chrome = window.chrome || { runtime: {} };
                const originalQuery = window.navigator.permissions && window.navigator.permissions.query;
                if (originalQuery) {
                  window.navigator.permissions.query = (parameters) => (
                    parameters && parameters.name === 'notifications'
                      ? Promise.resolve({ state: Notification.permission })
                      : originalQuery(parameters)
                  );
                }
                """;
    }

    public record StartedAuth(String flowId, String normalizedPhone, String message) {
    }

    private record PendingFlow(
            Playwright playwright,
            Browser browser,
            BrowserContext context,
            Page page,
            String phoneNumber,
            OffsetDateTime expiresAt
    ) {
    }
}
