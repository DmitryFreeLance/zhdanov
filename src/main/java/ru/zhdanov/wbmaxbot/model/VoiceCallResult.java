package ru.zhdanov.wbmaxbot.model;

public record VoiceCallResult(
        boolean success,
        String provider,
        String externalId,
        String details
) {
    public static VoiceCallResult success(String provider, String externalId, String details) {
        return new VoiceCallResult(true, provider, externalId, details);
    }

    public static VoiceCallResult failure(String provider, String details) {
        return new VoiceCallResult(false, provider, null, details);
    }
}
