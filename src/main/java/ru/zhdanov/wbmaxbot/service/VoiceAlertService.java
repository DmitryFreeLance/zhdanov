package ru.zhdanov.wbmaxbot.service;

import org.springframework.stereotype.Service;
import ru.zhdanov.wbmaxbot.config.AppProperties;
import ru.zhdanov.wbmaxbot.model.VoiceCallResult;
import ru.zhdanov.wbmaxbot.telephony.NoopTelephonyProvider;
import ru.zhdanov.wbmaxbot.telephony.TelephonyProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class VoiceAlertService {

    private final AppProperties properties;
    private final Map<String, TelephonyProvider> providers = new HashMap<>();
    private final NoopTelephonyProvider noopTelephonyProvider;

    public VoiceAlertService(AppProperties properties, List<TelephonyProvider> providers, NoopTelephonyProvider noopTelephonyProvider) {
        this.properties = properties;
        this.noopTelephonyProvider = noopTelephonyProvider;
        for (TelephonyProvider provider : providers) {
            this.providers.put(provider.providerName(), provider);
        }
    }

    public VoiceCallResult callAllTargets(String spokenText) {
        TelephonyProvider provider = providers.getOrDefault(properties.getTelephony().getProvider(), noopTelephonyProvider);
        if (!provider.isConfigured()) {
            return VoiceCallResult.failure(provider.providerName(), "Provider is not configured");
        }

        VoiceCallResult lastResult = VoiceCallResult.failure(provider.providerName(), "No target numbers configured");
        for (String targetNumber : properties.getTelephony().getTargetNumbers()) {
            lastResult = provider.call(targetNumber, spokenText);
        }
        return lastResult;
    }

    public VoiceCallResult callTarget(String targetNumber, String spokenText) {
        TelephonyProvider provider = providers.getOrDefault(properties.getTelephony().getProvider(), noopTelephonyProvider);
        if (!provider.isConfigured()) {
            return VoiceCallResult.failure(provider.providerName(), "Provider is not configured");
        }
        if (targetNumber == null || targetNumber.isBlank()) {
            return VoiceCallResult.failure(provider.providerName(), "No target number configured");
        }
        return provider.call(targetNumber, spokenText);
    }
}
