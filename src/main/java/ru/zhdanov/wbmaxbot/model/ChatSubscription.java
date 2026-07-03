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
        String alertParking,
        boolean callEnabled,
        String phoneNumber,
        String callTimeWindowStart,
        String callTimeWindowEnd,
        int callMaxDailyAttempts,
        int callAnswerCooldownMinutes,
        OffsetDateTime lastCallAnsweredAt,
        String pendingInputState,
        String pendingWbAuthFlowId,
        String pendingWbAuthPhoneNumber,
        OffsetDateTime createdAt,
        OffsetDateTime lastSeenAt
) {
}
