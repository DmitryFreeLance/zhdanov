package ru.zhdanov.wbmaxbot.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.OffsetDateTime;

@Repository
public class VoiceCallAttemptRepository {

    private final JdbcTemplate jdbcTemplate;

    public VoiceCallAttemptRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long createAttempt(OffsetDateTime now,
                              long chatId,
                              long accountId,
                              String triggerType,
                              String phoneNumber,
                              String provider,
                              String externalId,
                              String status,
                              boolean callStarted,
                              String details) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into voice_call_attempt(
                        created_at, updated_at, chat_id, account_id, trigger_type,
                        phone_number, provider, external_id, status, call_started, answered_by_human, details
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, now.toString());
            statement.setString(2, now.toString());
            statement.setLong(3, chatId);
            statement.setLong(4, accountId);
            statement.setString(5, triggerType);
            statement.setString(6, phoneNumber);
            statement.setString(7, provider);
            statement.setString(8, externalId);
            statement.setString(9, status);
            statement.setInt(10, callStarted ? 1 : 0);
            statement.setInt(11, 0);
            statement.setString(12, details);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Не удалось сохранить попытку дозвона");
        }
        return key.longValue();
    }

    public void updateOutcome(long attemptId,
                              OffsetDateTime now,
                              String provider,
                              String externalId,
                              String status,
                              boolean answeredByHuman,
                              String details) {
        jdbcTemplate.update("""
                update voice_call_attempt
                set updated_at = ?,
                    provider = ?,
                    external_id = ?,
                    status = ?,
                    answered_by_human = ?,
                    details = ?
                where id = ?
                """,
                now.toString(),
                provider,
                externalId,
                status,
                answeredByHuman ? 1 : 0,
                details,
                attemptId
        );
    }

    public int countStartedAutoAttemptsForChatAccount(long chatId,
                                                      long accountId,
                                                      OffsetDateTime fromInclusive,
                                                      OffsetDateTime toExclusive) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from voice_call_attempt
                where trigger_type = 'auto'
                  and chat_id = ?
                  and account_id = ?
                  and call_started = 1
                  and created_at >= ?
                  and created_at < ?
                """,
                Integer.class,
                chatId,
                accountId,
                fromInclusive.toString(),
                toExclusive.toString()
        );
        return count == null ? 0 : count;
    }

    public int countStartedAutoAttemptsForAccount(long accountId,
                                                  OffsetDateTime fromInclusive,
                                                  OffsetDateTime toExclusive) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from voice_call_attempt
                where trigger_type = 'auto'
                  and account_id = ?
                  and call_started = 1
                  and created_at >= ?
                  and created_at < ?
                """,
                Integer.class,
                accountId,
                fromInclusive.toString(),
                toExclusive.toString()
        );
        return count == null ? 0 : count;
    }
}
