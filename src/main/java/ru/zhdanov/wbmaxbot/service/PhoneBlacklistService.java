package ru.zhdanov.wbmaxbot.service;

import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class PhoneBlacklistService {

    private static final Set<String> BLACKLISTED_NUMBERS = Set.of(
            "79082783273"
    );

    public boolean isBlacklisted(String phoneNumber) {
        String normalized = normalizeForComparison(phoneNumber);
        return normalized != null && BLACKLISTED_NUMBERS.contains(normalized);
    }

    public String buildBlockedMessage() {
        return "Заданный номер в чёрном списке. Звонок на него запрещён.";
    }

    public String buildBlockedAutoCallMessage() {
        return "Заданный номер в чёрном списке. Автозвонок не будет выполнен.";
    }

    private String normalizeForComparison(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String digits = value.replaceAll("\\D", "");
        if (digits.length() == 10) {
            return "7" + digits;
        }
        if (digits.length() == 11 && digits.startsWith("8")) {
            return "7" + digits.substring(1);
        }
        if (digits.length() == 11 && digits.startsWith("7")) {
            return digits;
        }
        return digits;
    }
}
