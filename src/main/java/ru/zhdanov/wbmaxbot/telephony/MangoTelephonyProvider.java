package ru.zhdanov.wbmaxbot.telephony;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class MangoTelephonyProvider implements TelephonyProvider {

    private static final String CALLBACK_PATH = "/vpbx/commands/callback";

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public MangoTelephonyProvider(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public String providerName() {
        return "mango";
    }

    @Override
    public boolean isConfigured() {
        AppProperties.Mango mango = properties.getTelephony().getMango();
        return hasText(mango.getApiKey()) &&
                hasText(mango.getApiSalt()) &&
                (hasText(mango.getExtension()) || hasText(mango.getFromNumber()));
    }

    @Override
    public VoiceCallResult call(String targetNumber, String spokenText) {
        if (!isConfigured()) {
            return VoiceCallResult.failure(providerName(), "MANGO OFFICE is not configured");
        }

        AppProperties.Mango mango = properties.getTelephony().getMango();
        String commandId = "wb-max-" + UUID.randomUUID();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("command_id", commandId);
        payload.put("from", buildFromPayload(mango));
        payload.put("to_number", normalizePhoneNumber(targetNumber));
        if (hasText(mango.getLineNumber())) {
            payload.put("line_number", normalizePhoneNumber(mango.getLineNumber()));
        }

        try {
            String json = objectMapper.writeValueAsString(payload);
            String sign = sha256Hex(mango.getApiKey() + json + mango.getApiSalt());
            String form = "vpbx_api_key=" + encode(mango.getApiKey()) +
                    "&sign=" + encode(sign) +
                    "&json=" + encode(json);

            HttpRequest request = HttpRequest.newBuilder(URI.create(mango.getBaseUrl() + CALLBACK_PATH))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return VoiceCallResult.failure(providerName(), "MANGO OFFICE HTTP " + response.statusCode() + ": " + response.body());
            }
            return parseResponse(response.body(), commandId);
        } catch (JsonProcessingException e) {
            return VoiceCallResult.failure(providerName(), "Unable to build MANGO OFFICE request: " + e.getMessage());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return VoiceCallResult.failure(providerName(), e.getMessage());
        }
    }

    private Map<String, String> buildFromPayload(AppProperties.Mango mango) {
        Map<String, String> from = new LinkedHashMap<>();
        if (hasText(mango.getExtension())) {
            from.put("extension", mango.getExtension().trim());
        }
        if (hasText(mango.getFromNumber())) {
            from.put("number", normalizeAddress(mango.getFromNumber()));
        }
        return from;
    }

    private VoiceCallResult parseResponse(String body, String commandId) {
        try {
            JsonNode json = objectMapper.readTree(body);
            if (json.isObject()) {
                if (json.hasNonNull("error")) {
                    return VoiceCallResult.failure(providerName(), body);
                }
                if ("error".equalsIgnoreCase(json.path("status").asText())) {
                    return VoiceCallResult.failure(providerName(), body);
                }
                String externalId = firstNonBlank(
                        json.path("command_id").asText(null),
                        json.path("request_id").asText(null),
                        json.path("result").path("command_id").asText(null),
                        commandId
                );
                return VoiceCallResult.success(providerName(), externalId, body);
            }
        } catch (Exception ignored) {
            // Some MANGO OFFICE methods return plain text; keep the original body for diagnostics.
        }
        return VoiceCallResult.success(providerName(), commandId, body);
    }

    private String encode(String value) {
        return UriUtils.encodeQueryParam(value, StandardCharsets.UTF_8);
    }

    private String normalizeAddress(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.startsWith("sip:") || trimmed.contains("@")) {
            return trimmed;
        }
        return normalizePhoneNumber(trimmed);
    }

    private String normalizePhoneNumber(String value) {
        String digitsOnly = value == null ? "" : value.replaceAll("[^0-9]", "");
        return digitsOnly.isBlank() ? value : digitsOnly;
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to build MANGO OFFICE signature", e);
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
