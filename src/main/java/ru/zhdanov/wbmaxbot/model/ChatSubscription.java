package ru.zhdanov.wbmaxbot.model;

import java.time.OffsetDateTime;

public record ChatSubscription(
        long chatId,
        Long userId,
        String title,
        String chatType,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime lastSeenAt
) {
}
