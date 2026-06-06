package ru.zhdanov.wbmaxbot.model;

public record ReportSummary(
        String heading,
        long totalShk,
        long totalBoxes,
        long totalKgt,
        double totalVolumeLiters,
        int rowsCount
) {
}
