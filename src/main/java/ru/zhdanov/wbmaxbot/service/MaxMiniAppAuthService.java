package ru.zhdanov.wbmaxbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import ru.zhdanov.wbmaxbot.config.AppProperties;
import ru.zhdanov.wbmaxbot.model.MiniAppPrincipal;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MaxMiniAppAuthService {

    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public MaxMiniAppAuthService(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public MiniAppPrincipal validate(String initData) {
        if (initData == null || initData.isBlank()) {
            throw new IllegalArgumentException("initData is empty");
        }
        if (properties.getMax().getToken() == null || properties.getMax().getToken().isBlank()) {
            throw new IllegalStateException("MAX bot token is not configured");
        }

        Map<String, String> params = parseInitData(initData);
        String receivedHash = params.remove("hash");
        if (receivedHash == null || receivedHash.isBlank()) {
            throw new IllegalArgumentException("MAX initData hash is missing");
        }

        String calculatedHash = calculateHash(params, properties.getMax().getToken());
        if (!receivedHash.equalsIgnoreCase(calculatedHash)) {
            throw new IllegalArgumentException("MAX initData signature is invalid");
        }

        try {
            JsonNode chat = objectMapper.readTree(params.getOrDefault("chat", "{}"));
            JsonNode user = objectMapper.readTree(params.getOrDefault("user", "{}"));
            long chatId = chat.path("id").asLong(0);
            long userId = user.path("id").asLong(0);
            if (chatId <= 0 || userId <= 0) {
                throw new IllegalArgumentException("MAX initData does not contain chat/user ids");
            }
            return new MiniAppPrincipal(
                    chatId,
                    userId,
                    user.path("first_name").asText("MAX user"),
                    user.path("username").asText("")
            );
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            throw new IllegalStateException("Unable to parse MAX initData payload", e);
        }
    }

    private Map<String, String> parseInitData(String initData) {
        Map<String, String> params = new HashMap<>();
        for (String pair : initData.split("&")) {
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = pair.substring(0, separator);
            String value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            params.put(key, value);
        }
        return params;
    }

    private String calculateHash(Map<String, String> params, String botToken) {
        try {
            List<Map.Entry<String, String>> sorted = new ArrayList<>(params.entrySet());
            sorted.sort(Comparator.comparing(Map.Entry::getKey));
            StringBuilder launchParams = new StringBuilder();
            for (int i = 0; i < sorted.size(); i++) {
                Map.Entry<String, String> entry = sorted.get(i);
                if (i > 0) {
                    launchParams.append('\n');
                }
                launchParams.append(entry.getKey()).append('=').append(entry.getValue());
            }

            byte[] secretKey = hmacSha256("WebAppData".getBytes(StandardCharsets.UTF_8), botToken.getBytes(StandardCharsets.UTF_8));
            byte[] signature = hmacSha256(secretKey, launchParams.toString().getBytes(StandardCharsets.UTF_8));
            return toHex(signature);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to validate MAX initData hash", e);
        }
    }

    private byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte aByte : bytes) {
            builder.append(String.format("%02x", aByte));
        }
        return builder.toString();
    }
}
