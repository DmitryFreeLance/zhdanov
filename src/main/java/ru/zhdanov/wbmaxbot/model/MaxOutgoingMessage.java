package ru.zhdanov.wbmaxbot.model;

import java.util.List;
import java.util.Map;

public record MaxOutgoingMessage(
        String text,
        boolean notifyUsers,
        List<Map<String, Object>> attachments
) {
    public MaxOutgoingMessage(String text) {
        this(text, true, List.of());
    }
}
