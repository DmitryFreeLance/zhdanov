package ru.zhdanov.wbmaxbot.telephony;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.zhdanov.wbmaxbot.config.AppProperties;
import ru.zhdanov.wbmaxbot.model.VoiceCallResult;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ExolveTelephonyProvider implements TelephonyProvider {

    private static final String MAKE_VOICE_MESSAGE_PATH = "/call/v1/MakeVoiceMessage";
    private static final Logger log = LoggerFactory.getLogger(ExolveTelephonyProvider.class);

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ExolveTelephonyProvider(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String providerName() {
        return "exolve";
    }

    @Override
    public boolean isConfigured() {
        AppProperties.Exolve exolve = properties.getTelephony().getExolve();
        return hasText(exolve.getApiKey()) && hasText(exolve.getSourceNumber());
    }

    @Override
    public VoiceCallResult call(String targetNumber, String spokenText) {
        if (!isConfigured()) {
            return VoiceCallResult.failure(providerName(), "MTS Exolve is not configured");
        }

        AppProperties.Exolve exolve = properties.getTelephony().getExolve();
        String normalizedSource = normalizePhoneNumber(exolve.getSourceNumber());
        String normalizedDestination = normalizePhoneNumber(targetNumber);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", normalizedSource);
        payload.put("destination", normalizedDestination);
        String mode;
        if (hasText(exolve.getServiceId())) {
            payload.put("service_id", exolve.getServiceId().trim());
            mode = "service_id";
        } else {
            payload.put("tts", buildTtsPayload(exolve, spokenText));
            mode = "tts";
        }

        try {
            String json = objectMapper.writeValueAsString(payload);
            log.info("Starting Exolve voice call. source={}, destination={}, mode={}, textLength={}",
                    maskPhone(normalizedSource), maskPhone(normalizedDestination), mode, spokenText == null ? 0 : spokenText.length());
            HttpRequest request = HttpRequest.newBuilder(URI.create(exolve.getBaseUrl() + MAKE_VOICE_MESSAGE_PATH))
                    .header("Authorization", "Bearer " + exolve.getApiKey().trim())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("Exolve MakeVoiceMessage response. source={}, destination={}, mode={}, statusCode={}, body={}",
                    maskPhone(normalizedSource), maskPhone(normalizedDestination), mode, response.statusCode(), truncate(response.body()));
            if (response.statusCode() / 100 != 2) {
                return VoiceCallResult.failure(
                        providerName(),
                        "MTS Exolve HTTP " + response.statusCode() + ": " + response.body()
                );
            }

            JsonNode body = objectMapper.readTree(response.body());
            String callId = body.path("call_id").asText(null);
            if (!hasText(callId)) {
                return VoiceCallResult.failure(providerName(), "MTS Exolve response did not contain call_id: " + response.body());
            }
            log.info("Exolve voice call accepted. callId={}, source={}, destination={}, mode={}",
                    callId, maskPhone(normalizedSource), maskPhone(normalizedDestination), mode);
            return VoiceCallResult.success(providerName(), callId, response.body());
        } catch (IOException e) {
            log.warn("Exolve voice call IO failure. source={}, destination={}, mode={}, error={}",
                    maskPhone(normalizedSource), maskPhone(normalizedDestination), mode, e.getMessage());
            return VoiceCallResult.failure(providerName(), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Exolve voice call interrupted. source={}, destination={}, mode={}, error={}",
                    maskPhone(normalizedSource), maskPhone(normalizedDestination), mode, e.getMessage());
            return VoiceCallResult.failure(providerName(), e.getMessage());
        }
    }

    private Map<String, Object> buildTtsPayload(AppProperties.Exolve exolve, String spokenText) {
        Map<String, Object> tts = new LinkedHashMap<>();
        tts.put("text", spokenText);
        if (exolve.getVoice() != null) {
            tts.put("voice", exolve.getVoice());
        }
        if (exolve.getLang() != null) {
            tts.put("lang", exolve.getLang());
        }
        if (exolve.getVolume() != null) {
            tts.put("volume", exolve.getVolume());
        }
        if (exolve.getSpeed() != null) {
            tts.put("speed", exolve.getSpeed());
        }
        if (exolve.getEmotion() != null) {
            tts.put("emotion", exolve.getEmotion());
        }
        return tts;
    }

    private String normalizePhoneNumber(String value) {
        String digitsOnly = value == null ? "" : value.replaceAll("[^0-9]", "");
        return digitsOnly.isBlank() ? value : digitsOnly;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String maskPhone(String value) {
        String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
        if (digits.length() < 4) {
            return value;
        }
        return "+" + digits.charAt(0) + "***" + digits.substring(digits.length() - 4);
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 500 ? value.substring(0, 500) + "..." : value;
    }
}
