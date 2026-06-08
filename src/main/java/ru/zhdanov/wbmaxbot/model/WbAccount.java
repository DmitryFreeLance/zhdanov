package ru.zhdanov.wbmaxbot.model;

import java.time.OffsetDateTime;

public record WbAccount(
        long id,
        String phoneNumber,
        String storageStateJson,
        String status,
        String lastError,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime lastAuthenticatedAt
) {
}
