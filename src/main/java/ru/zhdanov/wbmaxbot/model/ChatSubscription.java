package ru.zhdanov.wbmaxbot.model;

import java.time.OffsetDateTime;

public record ChatSubscription(
        long chatId,
        Long userId,
        String title,
        String chatType,
        boolean active,
        boolean autoReportEnabled,
        int reportIntervalMinutes,
        OffsetDateTime lastReportSentAt,
        Integer shkThreshold,
        Double ratioThreshold,
        boolean callEnabled,
        String phoneNumber,
        String pendingInputState,
        String pendingWbAuthFlowId,
        String pendingWbAuthPhoneNumber,
        OffsetDateTime createdAt,
        OffsetDateTime lastSeenAt
) {
}
