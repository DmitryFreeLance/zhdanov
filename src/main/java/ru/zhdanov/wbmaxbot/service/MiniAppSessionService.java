package ru.zhdanov.wbmaxbot.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.zhdanov.wbmaxbot.model.MiniAppPrincipal;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MiniAppSessionService {

    private static final Duration TTL = Duration.ofHours(6);

    private final Map<String, SessionEntry> sessions = new ConcurrentHashMap<>();
    private final ZoneId zoneId;

    public MiniAppSessionService(ru.zhdanov.wbmaxbot.config.AppProperties properties) {
        this.zoneId = ZoneId.of(properties.getZoneId());
    }

    public String create(MiniAppPrincipal principal) {
        String token = UUID.randomUUID().toString();
        sessions.put(token, new SessionEntry(principal, OffsetDateTime.now(zoneId).plus(TTL)));
        return token;
    }

    public MiniAppPrincipal getRequired(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Сессия mini app не найдена. Закройте и заново откройте mini app из MAX.");
        }
        SessionEntry entry = sessions.get(token);
        if (entry == null || entry.expiresAt().isBefore(OffsetDateTime.now(zoneId))) {
            sessions.remove(token);
            throw new IllegalArgumentException("Mini app session expired. Reopen the app from MAX.");
        }
        return entry.principal();
    }

    @Scheduled(fixedDelay = 300000)
    public void cleanupExpired() {
        OffsetDateTime now = OffsetDateTime.now(zoneId);
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private record SessionEntry(MiniAppPrincipal principal, OffsetDateTime expiresAt) {
    }
}
