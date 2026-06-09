package ru.zhdanov.wbmaxbot.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Proxy;
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
import java.nio.file.StandardOpenOption;
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
    private static final String LOGIN_URL = "https://logistics.wildberries.ru/";
    private static final Duration FLOW_TTL = Duration.ofMinutes(10);
    private static final int AUTH_START_ATTEMPTS = 2;
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
        IllegalStateException lastError = null;

        for (int attempt = 1; attempt <= AUTH_START_ATTEMPTS; attempt++) {
            log.info("Starting WB auth flow for {} (attempt {}/{})", maskPhone(normalizedPhone), attempt, AUTH_START_ATTEMPTS);
            try {
                return startAttempt(normalizedPhone, attempt);
            } catch (IllegalStateException e) {
                lastError = e;
                log.warn("WB auth start attempt {} failed for {}: {}", attempt, maskPhone(normalizedPhone), e.getMessage());
                if (attempt < AUTH_START_ATTEMPTS && shouldRetryStart(e)) {
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                break;
            }
        }

        throw new IllegalStateException("Не удалось начать авторизацию WB: "
                + (lastError == null ? "неизвестная ошибка" : lastError.getMessage()), lastError);
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
        String proxyServer = properties.getWildberries().getProxyServer();
        if (proxyServer != null && !proxyServer.isBlank()) {
            Proxy proxy = new Proxy(proxyServer);
            if (properties.getWildberries().getProxyUsername() != null && !properties.getWildberries().getProxyUsername().isBlank()) {
                proxy.setUsername(properties.getWildberries().getProxyUsername());
            }
            if (properties.getWildberries().getProxyPassword() != null && !properties.getWildberries().getProxyPassword().isBlank()) {
                proxy.setPassword(properties.getWildberries().getProxyPassword());
            }
            options.setProxy(proxy);
        }
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
        String phoneDigitsForField = localPhoneDigitsForField(phoneNumber);
        typePhoneIntoMaskedField(phoneInput, phoneDigitsForField);
        String actualValue = phoneInput.inputValue();
        String actualDigits = digitsOnly(actualValue);
        if (!actualDigits.endsWith(phoneDigitsForField)) {
            throw new IllegalStateException("WB поле телефона приняло номер некорректно: " + actualValue);
        }
        page.waitForTimeout(300);
        clickFirst(page,
                "button:has-text('Получить код')",
                "button:has-text('Продолжить')",
                "button:has-text('Далее')",
                "button:has-text('Войти')",
                "input[type='submit']"
        );
    }

    private void typePhoneIntoMaskedField(Locator phoneInput, String phoneDigitsForField) {
        phoneInput.click();
        phoneInput.press("ControlOrMeta+A");
        phoneInput.press("Delete");
        phoneInput.fill("");
        phoneInput.type(phoneDigitsForField, new Locator.TypeOptions().setDelay(90));
    }

    private void waitForCodeUi(Page page, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (hasCodeInput(page)) {
                return;
            }
            String hint = extractCodeScreenHint(page);
            if (hint.contains("Не удалось запросить код")) {
                throw new IllegalStateException("WB вернул ошибку после отправки номера: " + hint);
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
        try {
            return page.locator(selector).count();
        } catch (RuntimeException e) {
            if (isTransientPlaywrightNavigationError(e)) {
                log.debug("Ignoring transient Playwright navigation error while counting '{}': {}", selector, e.getMessage());
                return 0;
            }
            throw e;
        }
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

    private String localPhoneDigitsForField(String normalizedPhone) {
        String digits = digitsOnly(normalizedPhone);
        if (digits.length() != 11 || !digits.startsWith("7")) {
            throw new IllegalArgumentException("Введите номер WB в формате +79991234567");
        }
        return digits.substring(1);
    }

    private String digitsOnly(String value) {
        return value == null ? "" : value.replaceAll("[^0-9]", "");
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

    private boolean isTransientPlaywrightNavigationError(RuntimeException error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        return message.contains("Execution context was destroyed")
                || message.contains("Most likely the page has been closed")
                || message.contains("Target page, context or browser has been closed")
                || message.contains("Cannot find context with specified id");
    }

    private String buildStealthInitScript() {
        return """
                Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                Object.defineProperty(navigator, 'platform', { get: () => 'Win32' });
                Object.defineProperty(navigator, 'language', { get: () => 'ru-RU' });
                Object.defineProperty(navigator, 'languages', { get: () => ['ru-RU', 'ru', 'en-US', 'en'] });
                Object.defineProperty(navigator, 'hardwareConcurrency', { get: () => 8 });
                Object.defineProperty(navigator, 'deviceMemory', { get: () => 8 });
                Object.defineProperty(navigator, 'userAgentData', {
                  get: () => ({
                    brands: [
                      { brand: 'Chromium', version: '126' },
                      { brand: 'Google Chrome', version: '126' },
                      { brand: 'Not.A/Brand', version: '24' }
                    ],
                    mobile: false,
                    platform: 'Windows',
                    getHighEntropyValues: async () => ({
                      architecture: 'x86',
                      bitness: '64',
                      mobile: false,
                      model: '',
                      platform: 'Windows',
                      platformVersion: '10.0.0',
                      uaFullVersion: '126.0.0.0'
                    })
                  })
                });
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

    private StartedAuth startAttempt(String normalizedPhone, int attempt) {
        Playwright playwright = null;
        Browser browser = null;
        BrowserContext context = null;
        Page page = null;
        boolean keepOpen = false;
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(defaultLaunchOptions(false));
            context = browser.newContext(defaultContextOptions());
            context.setExtraHTTPHeaders(Map.of(
                    "Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Upgrade-Insecure-Requests", "1",
                    "Sec-CH-UA", "\"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\", \"Not.A/Brand\";v=\"24\"",
                    "Sec-CH-UA-Mobile", "?0",
                    "Sec-CH-UA-Platform", "\"Windows\""
            ));
            context.addInitScript(buildStealthInitScript());
            page = context.newPage();
            page.navigate(LOGIN_URL,
                    new Page.NavigateOptions()
                            .setTimeout(timeoutMs())
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.waitForTimeout(1200);
            log.info("WB auth page opened for {} on attempt {}", maskPhone(normalizedPhone), attempt);

            waitForLoginUi(page, properties.getWildberries().getTimeout());
            fillPhoneAndRequestCode(page, normalizedPhone);
            log.info("WB phone submitted for {} on attempt {}", maskPhone(normalizedPhone), attempt);
            waitForCodeUi(page, properties.getWildberries().getTimeout());
            String codeScreenHint = extractCodeScreenHint(page);
            log.info("WB code input detected for {}. Hint: {}", maskPhone(normalizedPhone), codeScreenHint);

            String flowId = UUID.randomUUID().toString();
            pendingFlows.put(flowId, new PendingFlow(playwright, browser, context, page, normalizedPhone, OffsetDateTime.now(zoneId).plus(FLOW_TTL)));
            keepOpen = true;
            return new StartedAuth(flowId, normalizedPhone, codeScreenHint);
        } catch (Exception e) {
            String artifactHint = saveFailureArtifacts(page, normalizedPhone, attempt);
            String message = e.getMessage();
            if (artifactHint != null && !artifactHint.isBlank()) {
                message = (message == null || message.isBlank() ? "Ошибка авторизации WB" : message) + ". " + artifactHint;
            }
            throw new IllegalStateException(message, e);
        } finally {
            if (!keepOpen) {
                safelyClose(playwright, browser, context);
            }
        }
    }

    private boolean shouldRetryStart(IllegalStateException error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        return message.contains("Не удалось запросить код")
                || message.contains("Execution context was destroyed")
                || message.contains("Не удалось дождаться формы входа");
    }

    private void safelyClose(Playwright playwright, Browser browser, BrowserContext context) {
        try {
            if (context != null) {
                context.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (browser != null) {
                browser.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (Exception ignored) {
        }
    }

    private String saveFailureArtifacts(Page page, String normalizedPhone, int attempt) {
        if (page == null) {
            return "";
        }
        try {
            Path artifactsDir = properties.getWildberries().getStorageStatePath()
                    .toAbsolutePath()
                    .normalize()
                    .getParent()
                    .resolve("wb-auth-failures");
            Files.createDirectories(artifactsDir);

            String maskedPhone = normalizedPhone.replaceAll("[^0-9]", "");
            if (maskedPhone.length() > 4) {
                maskedPhone = maskedPhone.substring(maskedPhone.length() - 4);
            }
            String prefix = OffsetDateTime.now(zoneId).toEpochSecond() + "-a" + attempt + "-" + maskedPhone;
            Path screenshotPath = artifactsDir.resolve(prefix + ".png");
            Path textPath = artifactsDir.resolve(prefix + ".txt");

            page.screenshot(new Page.ScreenshotOptions().setPath(screenshotPath).setFullPage(true));
            String pageText = extractCodeScreenHint(page) + System.lineSeparator() + "URL: " + page.url();
            Files.writeString(textPath, pageText, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Saved WB auth failure artifacts: {} and {}", screenshotPath, textPath);
            return "Скриншот ошибки сохранён: " + screenshotPath;
        } catch (Exception artifactError) {
            log.warn("Failed to save WB auth failure artifacts: {}", artifactError.getMessage());
            return "";
        }
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
