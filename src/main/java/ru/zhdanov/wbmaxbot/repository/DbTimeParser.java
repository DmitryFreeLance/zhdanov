package ru.zhdanov.wbmaxbot.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

final class DbTimeParser {

    private DbTimeParser() {
    }

    static OffsetDateTime parseRequired(String value) {
        OffsetDateTime parsed = parseNullable(value);
        if (parsed == null) {
            throw new IllegalArgumentException("DB timestamp is empty");
        }
        return parsed;
    }

    static OffsetDateTime parseNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String trimmed = value.trim();
        try {
            return OffsetDateTime.parse(trimmed);
        } catch (RuntimeException ignored) {
            // fall through
        }

        try {
            return LocalDateTime.parse(trimmed.replace(' ', 'T')).atOffset(ZoneOffset.UTC);
        } catch (RuntimeException ignored) {
            // fall through
        }

        try {
            return LocalDate.parse(trimmed).atStartOfDay().atOffset(ZoneOffset.UTC);
        } catch (RuntimeException ignored) {
            // fall through
        }

        throw new IllegalArgumentException("Unsupported DB timestamp format: " + trimmed);
    }
}
