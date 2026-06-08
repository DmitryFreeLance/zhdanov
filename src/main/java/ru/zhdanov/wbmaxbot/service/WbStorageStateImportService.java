package ru.zhdanov.wbmaxbot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WbStorageStateImportService {

    private static final String BROWSER_EXPORT_KIND = "wb-miniapp-browser-export";

    private final ObjectMapper objectMapper;

    public WbStorageStateImportService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String normalize(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            throw new IllegalArgumentException("JSON WB-сессии пустой.");
        }

        Map<String, Object> root = parseObject(rawJson);
        if (looksLikePlaywrightStorageState(root)) {
            return writeJson(root);
        }
        if (BROWSER_EXPORT_KIND.equals(root.get("exportKind"))) {
            return writeJson(convertBrowserExport(root));
        }
        throw new IllegalArgumentException("Неизвестный формат WB-сессии. Загрузите storageState.json или экспорт из помощника mini app.");
    }

    private Map<String, Object> convertBrowserExport(Map<String, Object> root) {
        String url = asText(root.get("url"));
        String hostname = asText(root.get("hostname"));
        String origin = asText(root.get("origin"));
        boolean secure = url.startsWith("https://");

        List<Map<String, Object>> cookies = new ArrayList<>();
        Object rawCookies = root.get("cookies");
        if (rawCookies instanceof Map<?, ?> cookiesMap) {
            for (Map.Entry<?, ?> entry : cookiesMap.entrySet()) {
                String name = String.valueOf(entry.getKey());
                String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
                if (!name.isBlank()) {
                    cookies.add(cookie(hostname, name, value, secure));
                }
            }
        }

        if (cookies.isEmpty()) {
            throw new IllegalArgumentException("В экспорте нет cookies. Откройте WB после входа и повторите экспорт.");
        }

        List<Map<String, Object>> localStorage = new ArrayList<>();
        Object rawLocalStorage = root.get("localStorage");
        if (rawLocalStorage instanceof Map<?, ?> localStorageMap) {
            for (Map.Entry<?, ?> entry : localStorageMap.entrySet()) {
                String name = String.valueOf(entry.getKey());
                String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
                localStorage.add(Map.of(
                        "name", name,
                        "value", value
                ));
            }
        }

        Map<String, Object> storageState = new LinkedHashMap<>();
        storageState.put("cookies", cookies);
        storageState.put("origins", List.of(Map.of(
                "origin", origin,
                "localStorage", localStorage
        )));
        return storageState;
    }

    private Map<String, Object> cookie(String hostname, String name, String value, boolean secure) {
        Map<String, Object> cookie = new LinkedHashMap<>();
        cookie.put("name", name);
        cookie.put("value", value);
        cookie.put("domain", hostname);
        cookie.put("path", "/");
        cookie.put("expires", -1);
        cookie.put("httpOnly", false);
        cookie.put("secure", secure);
        cookie.put("sameSite", "Lax");
        return cookie;
    }

    private boolean looksLikePlaywrightStorageState(Map<String, Object> root) {
        return root.containsKey("cookies") && root.containsKey("origins");
    }

    private Map<String, Object> parseObject(String rawJson) {
        try {
            return objectMapper.readValue(rawJson, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Не удалось прочитать JSON WB-сессии.", e);
        }
    }

    private String writeJson(Map<String, Object> root) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Не удалось подготовить JSON WB-сессии.", e);
        }
    }

    private String asText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
