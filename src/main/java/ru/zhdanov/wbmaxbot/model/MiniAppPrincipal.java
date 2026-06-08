package ru.zhdanov.wbmaxbot.model;

public record MiniAppPrincipal(
        long chatId,
        long userId,
        String firstName,
        String username
) {
}
