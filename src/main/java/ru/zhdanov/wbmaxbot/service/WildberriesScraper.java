package ru.zhdanov.wbmaxbot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.zhdanov.wbmaxbot.config.AppProperties;
import ru.zhdanov.wbmaxbot.model.ReportRow;
import ru.zhdanov.wbmaxbot.model.ReportSummary;
import ru.zhdanov.wbmaxbot.model.ScrapeResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class WildberriesScraper {

    private static final Logger log = LoggerFactory.getLogger(WildberriesScraper.class);

    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public WildberriesScraper(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public ScrapeResult scrapeReport() {
        Path storageStatePath = ensureStorageStateParent();
        if (!Files.exists(storageStatePath)) {
            throw new IllegalStateException("WB session file not found: " + storageStatePath + ". Run bootstrap mode first.");
        }

        return scrapeReport(storageStatePath);
    }

    public ScrapeResult scrapeReport(String storageStateJson) {
        if (storageStateJson == null || storageStateJson.isBlank()) {
            throw new IllegalStateException("WB session storage state is empty");
        }

        try {
            Path tempFile = Files.createTempFile("wb-storage-state-", ".json");
            Files.writeString(tempFile, storageStateJson);
            try {
                return scrapeReport(tempFile);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to use WB storage state from database", e);
        }
    }

    private ScrapeResult scrapeReport(Path storageStatePath) {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(defaultLaunchOptions(properties.getWildberries().isHeadless()));
            Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
                    .setLocale("ru-RU")
                    .setStorageStatePath(storageStatePath);
            try (BrowserContext context = browser.newContext(contextOptions)) {
                context.addInitScript("""
                        Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                        """);
                Page page = context.newPage();
                try {
                    page.navigate(properties.getWildberries().getReportUrl(),
                            new Page.NavigateOptions()
                                    .setTimeout(timeoutMs())
                                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                } catch (RuntimeException e) {
                    throw new IllegalStateException(buildNavigationTimeoutMessage(page), e);
                }
                long deadline = System.currentTimeMillis() + properties.getWildberries().getTimeout().toMillis();
                waitForReportShell(page, deadline);

                if (page.url().contains("/auth/login")) {
                    throw new IllegalStateException("WB session expired: page redirected to login");
                }

                String json = waitForReportDataAndExtract(page, deadline);
                context.storageState(new BrowserContext.StorageStateOptions().setPath(storageStatePath));
                ScrapeResult result = parseScrapeResult(json);
                log.info("WB scrape result ready. heading='{}', rows={}, totalShk={}, totalBoxes={}, url={}",
                        blankFallback(result.summary().heading()),
                        result.summary().rowsCount(),
                        result.summary().totalShk(),
                        result.summary().totalBoxes(),
                        blankFallback(page.url()));
                return result;
            }
        }
    }

    public Path bootstrapSession() {
        Path storageStatePath = ensureStorageStateParent();
        log.info("Starting WB bootstrap flow. A browser window will open for manual login.");

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(defaultLaunchOptions(false));
            try (BrowserContext context = browser.newContext(new Browser.NewContextOptions().setLocale("ru-RU"))) {
                context.addInitScript("""
                        Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                        """);
                Page page = context.newPage();
                page.navigate(properties.getWildberries().getReportUrl(),
                        new Page.NavigateOptions()
                                .setTimeout(timeoutMs())
                                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

                long deadline = System.currentTimeMillis() + properties.getWildberries().getBootstrapTimeout().toMillis();
                while (System.currentTimeMillis() < deadline) {
                    if (page.url().contains("/reports/remainders/last-mile/chart") && count(page, "table") > 0) {
                        context.storageState(new BrowserContext.StorageStateOptions().setPath(storageStatePath));
                        log.info("WB session saved to {}", storageStatePath.toAbsolutePath());
                        return storageStatePath;
                    }
                    page.waitForTimeout(1000);
                }
                throw new IllegalStateException("Timed out waiting for manual Wildberries login");
            }
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

    private void waitForReportShell(Page page, long deadline) {
        while (System.currentTimeMillis() < deadline) {
            if (page.url().contains("/auth/login")) {
                return;
            }
            if (count(page, "table") > 0 && count(page, "h1") > 0) {
                return;
            }
            page.waitForTimeout(1000);
        }
        throw new IllegalStateException(buildReportTimeoutMessage(page));
    }

    private String waitForReportDataAndExtract(Page page, long deadline) {
        ReportProbe lastProbe = null;
        while (System.currentTimeMillis() < deadline) {
            if (page.url().contains("/auth/login")) {
                return (String) page.evaluate(EXTRACT_REPORT_SCRIPT);
            }
            ReportProbe probe = readReportProbe(page);
            lastProbe = probe;
            if (probe.hasDataRows()) {
                return (String) page.evaluate(EXTRACT_REPORT_SCRIPT);
            }
            page.waitForTimeout(1000);
        }

        if (lastProbe != null) {
            log.warn("WB report page stayed without data rows until timeout. title='{}', heading='{}', tableFound={}, dataRows={}, body='{}'",
                    blankFallback(safeEvaluate(page, "() => document.title")),
                    blankFallback(lastProbe.heading()),
                    lastProbe.tableFound(),
                    lastProbe.dataRowCount(),
                    blankFallback(lastProbe.bodyText()));
        }
        return (String) page.evaluate(EXTRACT_REPORT_SCRIPT);
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

    private String buildReportTimeoutMessage(Page page) {
        String url = safePageValue(page, Page::url);
        String title = safeEvaluate(page, "() => document.title");
        String heading = safeEvaluate(page, "() => document.querySelector('h1, h2')?.textContent || ''");
        String bodyText = safeEvaluate(page, """
                () => (document.body?.innerText || '')
                  .replace(/\\s+/g, ' ')
                  .trim()
                  .slice(0, 500)
                """);
        return ("Timed out waiting for WB report table. URL: " + blankFallback(url)
                + ". Title: " + blankFallback(title)
                + ". Heading: " + blankFallback(heading)
                + ". Screen: " + blankFallback(bodyText)).trim();
    }

    private String buildNavigationTimeoutMessage(Page page) {
        String url = safePageValue(page, Page::url);
        String title = safeEvaluate(page, "() => document.title");
        return ("WB report page did not open in time. URL: " + blankFallback(url)
                + ". Title: " + blankFallback(title)).trim();
    }

    private String safePageValue(Page page, java.util.function.Function<Page, String> extractor) {
        try {
            return extractor.apply(page);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private String safeEvaluate(Page page, String script) {
        try {
            Object value = page.evaluate(script);
            return value == null ? "" : String.valueOf(value).trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private String blankFallback(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private ReportProbe readReportProbe(Page page) {
        try {
            String json = String.valueOf(page.evaluate(EXTRACT_REPORT_PROBE_SCRIPT));
            return objectMapper.readValue(json, ReportProbe.class);
        } catch (Exception e) {
            log.debug("Unable to read WB report probe: {}", e.getMessage());
            return new ReportProbe(false, "", 0, "");
        }
    }

    private ScrapeResult parseScrapeResult(String json) {
        try {
            Map<String, Object> root = objectMapper.readValue(json, new TypeReference<>() {
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rowsRaw = (List<Map<String, Object>>) root.getOrDefault("rows", List.of());
            List<ReportRow> rows = new ArrayList<>();
            for (Map<String, Object> row : rowsRaw) {
                rows.add(new ReportRow(
                        asText(row.get("loName")),
                        asText(row.get("autoRequests")),
                        asText(row.get("pickupTime")),
                        asText(row.get("route")),
                        asText(row.get("parking")),
                        asInt(row.get("boxes")),
                        asInt(row.get("kgt")),
                        asInt(row.get("shk")),
                        asInt(row.get("norm")),
                        asDouble(row.get("ratio")),
                        asNullableDouble(row.get("volumeLiters")),
                        asNullableDouble(row.get("averageAccumulationLiters")),
                        asNullableDouble(row.get("distanceKm")),
                        objectMapper.writeValueAsString(row)
                ));
            }

            ReportSummary summary = new ReportSummary(
                    asText(root.get("heading")),
                    asLong(root.get("totalShk")),
                    asLong(root.get("totalBoxes")),
                    asLong(root.get("totalKgt")),
                    asDouble(root.get("totalVolumeLiters")),
                    rows.size()
            );

            return new ScrapeResult(
                    OffsetDateTime.now(ZoneId.of(properties.getZoneId())),
                    summary,
                    rows
            );
        } catch (IOException e) {
            throw new IllegalStateException("Unable to parse WB scrape result", e);
        }
    }

    private long timeoutMs() {
        return properties.getWildberries().getTimeout().toMillis();
    }

    private Path ensureStorageStateParent() {
        try {
            Path storageStatePath = properties.getWildberries().getStorageStatePath().toAbsolutePath().normalize();
            Files.createDirectories(storageStatePath.getParent());
            return storageStatePath;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create storage state directory", e);
        }
    }

    private String asText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private Double asNullableDouble(Object value) {
        return value == null ? null : asDouble(value);
    }

    private record ReportProbe(
            boolean tableFound,
            String heading,
            int dataRowCount,
            String bodyText
    ) {
        boolean hasDataRows() {
            return dataRowCount > 0;
        }
    }

    private static final String EXTRACT_REPORT_PROBE_SCRIPT = """
            () => JSON.stringify((() => {
              const normalize = (value) => (value || '').replace(/\\s+/g, ' ').trim();
              const table = document.querySelector('table');
              const rows = table ? Array.from(table.querySelectorAll('tbody tr')) : [];
              const dataRowCount = rows.filter((tr) => {
                const cells = tr.querySelectorAll('td');
                return cells.length >= 10 && normalize(tr.textContent).length > 0;
              }).length;
              return {
                tableFound: Boolean(table),
                heading: normalize(document.querySelector('h1, h2')?.textContent) || document.title || '',
                dataRowCount,
                bodyText: normalize(document.body?.innerText || '').slice(0, 400)
              };
            })())
            """;

    private static final String EXTRACT_REPORT_SCRIPT = """
            () => JSON.stringify((() => {
              const normalize = (value) => (value || '').replace(/\\s+/g, ' ').trim();
              const parseNumber = (value) => {
                const clean = String(value || '')
                  .replace(/\\s+/g, '')
                  .replace(/,/g, '.')
                  .replace(/[^0-9.\\-]/g, '');
                if (!clean) return 0;
                const result = Number(clean);
                return Number.isFinite(result) ? result : 0;
              };

              const heading = normalize(document.querySelector('h1')?.textContent) || document.title;
              const table = document.querySelector('table');
              if (!table) {
                return {
                  heading,
                  totalShk: 0,
                  totalBoxes: 0,
                  totalKgt: 0,
                  totalVolumeLiters: 0,
                  rows: []
                };
              }

              const rows = Array.from(table.querySelectorAll('tbody tr'))
                .filter((tr) => {
                  const cells = tr.querySelectorAll('td');
                  return cells.length >= 10 && normalize(tr.textContent).length > 0;
                })
                .map((tr) => {
                  const cells = Array.from(tr.querySelectorAll('td')).map((td) => normalize(td.textContent));
                  return {
                    loName: cells[1] || '',
                    autoRequests: cells[2] || '',
                    pickupTime: cells[3] || '',
                    route: cells[4] || '',
                    parking: cells[5] || '',
                    boxes: parseNumber(cells[6]),
                    kgt: parseNumber(cells[7]),
                    shk: parseNumber(cells[8]),
                    norm: parseNumber(cells[9]),
                    ratio: parseNumber(cells[9]) ? Number((parseNumber(cells[8]) / parseNumber(cells[9])).toFixed(4)) : 0,
                    volumeLiters: parseNumber(cells[10]),
                    averageAccumulationLiters: parseNumber(cells[11]),
                    distanceKm: parseNumber(cells[12])
                  };
                });

              const totals = rows.reduce((acc, row) => {
                acc.totalShk += row.shk;
                acc.totalBoxes += row.boxes;
                acc.totalKgt += row.kgt;
                acc.totalVolumeLiters += row.volumeLiters;
                return acc;
              }, { totalShk: 0, totalBoxes: 0, totalKgt: 0, totalVolumeLiters: 0 });

              return {
                heading,
                totalShk: totals.totalShk,
                totalBoxes: totals.totalBoxes,
                totalKgt: totals.totalKgt,
                totalVolumeLiters: Number(totals.totalVolumeLiters.toFixed(2)),
                rows
              };
            })())
            """;
}
