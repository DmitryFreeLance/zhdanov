package ru.zhdanov.wbmaxbot.model;

public record AlertTrigger(
        String dedupeKey,
        ReportRow row,
        String reason
) {
}
