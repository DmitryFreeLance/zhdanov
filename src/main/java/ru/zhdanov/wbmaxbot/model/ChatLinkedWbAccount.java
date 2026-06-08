package ru.zhdanov.wbmaxbot.model;

import java.time.OffsetDateTime;

public record ChatLinkedWbAccount(
        long chatId,
        long accountId,
        String phoneNumber,
        String storageStateJson,
        boolean enabled,
        String status,
        String lastError,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime lastAuthenticatedAt
) {
}
