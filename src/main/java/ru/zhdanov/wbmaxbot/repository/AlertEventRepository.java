package ru.zhdanov.wbmaxbot.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class AlertEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public AlertEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<OffsetDateTime> findLastAlertAt(String dedupeKey) {
        return jdbcTemplate.query("""
                        select created_at
                        from alert_event
                        where dedupe_key = ?
                        order by created_at desc
                        limit 1
                        """,
                rs -> rs.next() ? Optional.of(OffsetDateTime.parse(rs.getString("created_at"))) : Optional.empty(),
                dedupeKey
        );
    }

    public void save(OffsetDateTime createdAt,
                     String dedupeKey,
                     String route,
                     String parking,
                     int shk,
                     int norm,
                     double ratio,
                     String reason,
                     String messageStatus,
                     String voiceProvider,
                     String voiceStatus,
                     String voiceExternalId,
                     String details) {
        jdbcTemplate.update("""
                insert into alert_event(
                    created_at, dedupe_key, route, parking, shk, norm, ratio, reason,
                    message_status, voice_provider, voice_status, voice_external_id, details
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                createdAt.toString(),
                dedupeKey,
                route,
                parking,
                shk,
                norm,
                ratio,
                reason,
                messageStatus,
                voiceProvider,
                voiceStatus,
                voiceExternalId,
                details
        );
    }
}
