package ru.zhdanov.wbmaxbot.telephony;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;
import ru.zhdanov.wbmaxbot.config.AppProperties;
import ru.zhdanov.wbmaxbot.model.VoiceCallResult;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class TwilioTelephonyProvider implements TelephonyProvider {

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public TwilioTelephonyProvider(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public String providerName() {
        return "twilio";
    }

    @Override
    public boolean isConfigured() {
        AppProperties.Twilio twilio = properties.getTelephony().getTwilio();
        return hasText(twilio.getAccountSid()) && hasText(twilio.getAuthToken()) && hasText(twilio.getFromNumber());
    }

    @Override
    public VoiceCallResult call(String targetNumber, String spokenText) {
        if (!isConfigured()) {
            return VoiceCallResult.failure(providerName(), "Twilio is not configured");
        }

        AppProperties.Twilio twilio = properties.getTelephony().getTwilio();
        String twiml = "<Response><Say language=\"" + escapeXml(twilio.getLanguage()) + "\" voice=\"" +
                escapeXml(twilio.getVoice()) + "\">" + escapeXml(spokenText) + "</Say></Response>";
        String form = "To=" + encode(targetNumber) +
                "&From=" + encode(twilio.getFromNumber()) +
                "&Twiml=" + encode(twiml);

        String url = twilio.getBaseUrl() + "/Accounts/" + twilio.getAccountSid() + "/Calls.json";
        String basicAuth = Base64.getEncoder().encodeToString(
                (twilio.getAccountSid() + ":" + twilio.getAuthToken()).getBytes(StandardCharsets.UTF_8)
        );

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Basic " + basicAuth)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return VoiceCallResult.failure(providerName(), "Twilio HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode json = objectMapper.readTree(response.body());
            return VoiceCallResult.success(providerName(), json.path("sid").asText(null), response.body());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return VoiceCallResult.failure(providerName(), e.getMessage());
        }
    }

    private String encode(String value) {
        return UriUtils.encodeQueryParam(value, StandardCharsets.UTF_8);
    }

    private String escapeXml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
