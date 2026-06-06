package ru.zhdanov.wbmaxbot.telephony;

import org.springframework.stereotype.Component;
import ru.zhdanov.wbmaxbot.model.VoiceCallResult;

@Component
public class NoopTelephonyProvider implements TelephonyProvider {

    @Override
    public String providerName() {
        return "noop";
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public VoiceCallResult call(String targetNumber, String spokenText) {
        return VoiceCallResult.success(providerName(), "noop", "Call skipped by noop provider for " + targetNumber);
    }
}
