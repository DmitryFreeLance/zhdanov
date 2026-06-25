package ru.zhdanov.wbmaxbot.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AlertCallRequestService {

    private static final Duration REQUEST_TTL = Duration.ofHours(24);

    private final Map<String, AlertCallRequest> requests = new ConcurrentHashMap<>();

    public String create(long chatId, String phoneNumber, String spokenText) {
        cleanupExpired();
        String requestId = UUID.randomUUID().toString();
        requests.put(requestId, new AlertCallRequest(
                requestId,
                chatId,
                phoneNumber,
                spokenText == null ? "" : spokenText,
                OffsetDateTime.now(ZoneOffset.UTC)
        ));
        return requestId;
    }

    public AlertCallRequest get(String requestId) {
        cleanupExpired();
        AlertCallRequest request = requests.get(requestId);
        if (request == null || isExpired(request)) {
            requests.remove(requestId);
            return null;
        }
        return request;
    }

    public AlertCallRequest take(String requestId) {
        cleanupExpired();
        AlertCallRequest request = requests.remove(requestId);
        if (request == null || isExpired(request)) {
            return null;
        }
        return request;
    }

    private void cleanupExpired() {
        Iterator<Map.Entry<String, AlertCallRequest>> iterator = requests.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, AlertCallRequest> entry = iterator.next();
            if (isExpired(entry.getValue())) {
                iterator.remove();
            }
        }
    }

    private boolean isExpired(AlertCallRequest request) {
        return request.createdAt().plus(REQUEST_TTL).isBefore(OffsetDateTime.now(ZoneOffset.UTC));
    }

    public record AlertCallRequest(
            String requestId,
            long chatId,
            String phoneNumber,
            String spokenText,
            OffsetDateTime createdAt
    ) {
    }
}
