package ru.zhdanov.wbmaxbot.model;

public record ReportRow(
        String loName,
        String autoRequests,
        String pickupTime,
        String route,
        String parking,
        int boxes,
        int kgt,
        int shk,
        int norm,
        double ratio,
        Double volumeLiters,
        Double averageAccumulationLiters,
        Double distanceKm,
        String rawJson
) {
}
