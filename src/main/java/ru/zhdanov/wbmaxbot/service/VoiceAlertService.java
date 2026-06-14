package ru.zhdanov.wbmaxbot.service;

import org.springframework.stereotype.Service;
import ru.zhdanov.wbmaxbot.config.AppProperties;
import ru.zhdanov.wbmaxbot.model.VoiceCallResult;
import ru.zhdanov.wbmaxbot.telephony.NoopTelephonyProvider;
import ru.zhdanov.wbmaxbot.telephony.TelephonyProvider;

import java.util.ArrayList;
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

        int maxAttempts = Math.max(1, properties.getTelephony().getMaxAttempts());
        int retryDelaySeconds = Math.max(0, properties.getTelephony().getRetryDelaySeconds());
        VoiceCallResult lastResult = VoiceCallResult.failure(provider.providerName(), "Call was not attempted");
        List<String> attemptDetails = new ArrayList<>();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            lastResult = provider.call(targetNumber, spokenText);
            attemptDetails.add("attempt " + attempt + "/" + maxAttempts + ": " + safeDetails(lastResult.details()));
            if (lastResult.success()) {
                String details = appendAttemptSummary(lastResult.details(), attemptDetails);
                return VoiceCallResult.success(lastResult.provider(), lastResult.externalId(), details);
            }
            if (attempt < maxAttempts && retryDelaySeconds > 0) {
                try {
                    Thread.sleep(retryDelaySeconds * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return VoiceCallResult.failure(provider.providerName(), appendAttemptSummary("Retry interrupted", attemptDetails));
                }
            }
        }

        return VoiceCallResult.failure(lastResult.provider(), appendAttemptSummary(lastResult.details(), attemptDetails));
    }

    private String appendAttemptSummary(String details, List<String> attemptDetails) {
        String joined = String.join("; ", attemptDetails);
        if (details == null || details.isBlank()) {
            return joined;
        }
        return details + " | " + joined;
    }

    private String safeDetails(String details) {
        return details == null || details.isBlank() ? "no details" : details;
    }
}
