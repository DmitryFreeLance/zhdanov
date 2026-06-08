package ru.zhdanov.wbmaxbot.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
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
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WbLoginFlowService {

    private static final String LOGIN_URL = "https://logistics.wildberries.ru/auth/login";
    private static final Duration FLOW_TTL = Duration.ofMinutes(10);

    private final AppProperties properties;
    private final ZoneId zoneId;
    private final Map<String, PendingFlow> pendingFlows = new ConcurrentHashMap<>();

    public WbLoginFlowService(AppProperties properties) {
        this.properties = properties;
        this.zoneId = ZoneId.of(properties.getZoneId());
    }

    public StartedAuth start(String phoneNumber) {
        String normalizedPhone = normalizePhone(phoneNumber);

        try {
            Playwright playwright = Playwright.create();
            Browser browser = playwright.chromium().launch(defaultLaunchOptions(properties.getWildberries().isHeadless()));
            BrowserContext context = browser.newContext(new Browser.NewContextOptions().setLocale("ru-RU"));
            context.addInitScript("""
                    Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                    """);
            Page page = context.newPage();
            page.navigate(LOGIN_URL,
                    new Page.NavigateOptions()
                            .setTimeout(timeoutMs())
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            waitForLoginUi(page, properties.getWildberries().getTimeout());
            fillPhoneAndRequestCode(page, normalizedPhone);
            waitForCodeUi(page, properties.getWildberries().getTimeout());

            String flowId = UUID.randomUUID().toString();
            pendingFlows.put(flowId, new PendingFlow(playwright, browser, context, page, normalizedPhone, OffsetDateTime.now(zoneId).plus(FLOW_TTL)));
            return new StartedAuth(flowId, normalizedPhone, "Введите код из SMS и нажмите Подтвердить");
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось начать авторизацию WB: " + e.getMessage(), e);
        }
    }

    public String confirm(String flowId, String code) {
        PendingFlow flow = getRequired(flowId);
        try {
            submitCode(flow.page(), code);
            waitForAuthorizedPage(flow.page(), properties.getWildberries().getBootstrapTimeout());
            Path tempFile = Files.createTempFile("wb-auth-storage-", ".json");
            try {
                flow.context().storageState(new BrowserContext.StorageStateOptions().setPath(tempFile));
                return Files.readString(tempFile);
            } finally {
                Files.deleteIfExists(tempFile);
                closeFlow(flowId);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось подтвердить код WB: " + e.getMessage(), e);
        }
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
                        "--no-sandbox"
                ));
        String browserExecutablePath = properties.getWildberries().getBrowserExecutablePath();
        if (browserExecutablePath != null && !browserExecutablePath.isBlank()) {
            options.setExecutablePath(Path.of(browserExecutablePath));
        }
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
        first(page, "input[type='tel'], input[inputmode='tel'], input[name*='phone' i], input[autocomplete='tel'], input[placeholder*='тел' i]")
                .fill(phoneNumber);
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
        throw new IllegalStateException("Не удалось дождаться поля ввода кода WB");
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
            return "7" + digits;
        }
        if (digits.length() == 11 && digits.startsWith("8")) {
            return "7" + digits.substring(1);
        }
        if (digits.length() == 11 && digits.startsWith("7")) {
            return digits;
        }
        throw new IllegalArgumentException("Введите номер WB в формате +79991234567");
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
