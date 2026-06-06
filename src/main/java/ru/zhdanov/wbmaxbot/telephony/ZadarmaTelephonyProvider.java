package ru.zhdanov.wbmaxbot.telephony;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;
import ru.zhdanov.wbmaxbot.config.AppProperties;
import ru.zhdanov.wbmaxbot.model.VoiceCallResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

@Component
public class ZadarmaTelephonyProvider implements TelephonyProvider {

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ZadarmaTelephonyProvider(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public String providerName() {
        return "zadarma";
    }

    @Override
    public boolean isConfigured() {
        AppProperties.Zadarma zadarma = properties.getTelephony().getZadarma();
        return hasText(zadarma.getKey()) && hasText(zadarma.getSecret()) && hasText(zadarma.getFrom());
    }

    @Override
    public VoiceCallResult call(String targetNumber, String spokenText) {
        if (!isConfigured()) {
            return VoiceCallResult.failure(providerName(), "Zadarma is not configured");
        }

        AppProperties.Zadarma zadarma = properties.getTelephony().getZadarma();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("from", zadarma.getFrom());
        params.put("to", targetNumber);
        if (hasText(zadarma.getSip())) {
            params.put("sip", zadarma.getSip());
        }
        if (zadarma.isPredicted()) {
            params.put("predicted", "1");
        }

        String methodPath = "/v1/request/callback/";
        String queryString = buildSortedQuery(params);
        String signature = buildSignature(methodPath, queryString, zadarma.getSecret());

        HttpRequest request = HttpRequest.newBuilder(URI.create(zadarma.getBaseUrl() + methodPath + "?" + queryString))
                .header("Authorization", zadarma.getKey() + ":" + signature)
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return VoiceCallResult.failure(providerName(), "Zadarma HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode json = objectMapper.readTree(response.body());
            if (!"success".equalsIgnoreCase(json.path("status").asText())) {
                return VoiceCallResult.failure(providerName(), response.body());
            }
            String externalId = json.path("time").asText(null);
            return VoiceCallResult.success(providerName(), externalId, response.body());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return VoiceCallResult.failure(providerName(), e.getMessage());
        }
    }

    private String buildSortedQuery(Map<String, String> input) {
        Map<String, String> sorted = new TreeMap<>(input);
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            sb.append(UriUtils.encodeQueryParam(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(UriUtils.encodeQueryParam(entry.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        return sb.toString();
    }

    private String buildSignature(String methodPath, String params, String secret) {
        try {
            String payload = methodPath + params + md5Hex(params);
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to build Zadarma signature", e);
        }
    }

    private String md5Hex(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to build MD5 hash", e);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
