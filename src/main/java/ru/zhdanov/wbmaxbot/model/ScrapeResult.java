package ru.zhdanov.wbmaxbot.model;

import java.time.OffsetDateTime;
import java.util.List;

public record ScrapeResult(
        OffsetDateTime scrapedAt,
        ReportSummary summary,
        List<ReportRow> rows
) {
}
