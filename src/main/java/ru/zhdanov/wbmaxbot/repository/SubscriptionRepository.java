package ru.zhdanov.wbmaxbot.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.zhdanov.wbmaxbot.model.ChatSubscription;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class SubscriptionRepository {

    private final JdbcTemplate jdbcTemplate;

    public SubscriptionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsert(long chatId, Long userId, String title, String chatType, boolean active, OffsetDateTime now) {
        jdbcTemplate.update("""
                insert into chat_subscription(chat_id, user_id, title, chat_type, active, created_at, last_seen_at)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict(chat_id) do update set
                    user_id = excluded.user_id,
                    title = excluded.title,
                    chat_type = excluded.chat_type,
                    active = excluded.active,
                    last_seen_at = excluded.last_seen_at
                """,
                chatId,
                userId,
                title,
                chatType,
                active ? 1 : 0,
                now.toString(),
                now.toString()
        );
    }

    public void deactivate(long chatId, OffsetDateTime now) {
        jdbcTemplate.update("""
                update chat_subscription
                set active = 0, last_seen_at = ?
                where chat_id = ?
                """,
                now.toString(),
                chatId
        );
    }

    public List<ChatSubscription> findActive() {
        return jdbcTemplate.query("""
                select chat_id, user_id, title, chat_type, active, created_at, last_seen_at
                from chat_subscription
                where active = 1
                order by created_at
                """,
                (rs, rowNum) -> mapRow(rs)
        );
    }

    public Optional<ChatSubscription> findByChatId(long chatId) {
        List<ChatSubscription> rows = jdbcTemplate.query("""
                select chat_id, user_id, title, chat_type, active, created_at, last_seen_at
                from chat_subscription
                where chat_id = ?
                """,
                (rs, rowNum) -> mapRow(rs),
                chatId
        );
        return rows.stream().findFirst();
    }

    public int countActive() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from chat_subscription where active = 1
                """, Integer.class);
        return count == null ? 0 : count;
    }

    private ChatSubscription mapRow(ResultSet rs) throws SQLException {
        Number userIdValue = (Number) rs.getObject("user_id");
        return new ChatSubscription(
                rs.getLong("chat_id"),
                userIdValue == null ? null : userIdValue.longValue(),
                rs.getString("title"),
                rs.getString("chat_type"),
                rs.getInt("active") == 1,
                OffsetDateTime.parse(rs.getString("created_at")),
                OffsetDateTime.parse(rs.getString("last_seen_at"))
        );
    }
}
