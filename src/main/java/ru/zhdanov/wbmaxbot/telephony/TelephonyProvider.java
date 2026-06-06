package ru.zhdanov.wbmaxbot.telephony;

import ru.zhdanov.wbmaxbot.model.VoiceCallResult;

public interface TelephonyProvider {

    String providerName();

    boolean isConfigured();

    VoiceCallResult call(String targetNumber, String spokenText);
}
