package ru.zhdanov.wbmaxbot.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.zhdanov.wbmaxbot.model.ChatLinkedWbAccount;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class ChatWbAccountRepository {

    private final JdbcTemplate jdbcTemplate;

    public ChatWbAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void linkAccount(long chatId, long accountId, OffsetDateTime now) {
        jdbcTemplate.update("""
                insert into chat_wb_account(chat_id, account_id, enabled, created_at, updated_at)
                values (?, ?, 1, ?, ?)
                on conflict(chat_id, account_id) do update set
                    enabled = 1,
                    updated_at = excluded.updated_at
                """,
                chatId,
                accountId,
                now.toString(),
                now.toString()
        );
    }

    public List<ChatLinkedWbAccount> findByChatId(long chatId) {
        return jdbcTemplate.query("""
                select l.chat_id, l.account_id, l.enabled, l.created_at, l.updated_at,
                       a.phone_number, a.storage_state_json, a.status, a.last_error, a.last_authenticated_at
                from chat_wb_account l
                join wb_account a on a.id = l.account_id
                where l.chat_id = ?
                order by a.updated_at desc
                """,
                (rs, rowNum) -> mapRow(rs),
                chatId
        );
    }

    public List<ChatLinkedWbAccount> findEnabledByChatId(long chatId) {
        return jdbcTemplate.query("""
                select l.chat_id, l.account_id, l.enabled, l.created_at, l.updated_at,
                       a.phone_number, a.storage_state_json, a.status, a.last_error, a.last_authenticated_at
                from chat_wb_account l
                join wb_account a on a.id = l.account_id
                where l.chat_id = ? and l.enabled = 1
                order by a.updated_at desc
                """,
                (rs, rowNum) -> mapRow(rs),
                chatId
        );
    }

    public void updateEnabled(long chatId, long accountId, boolean enabled, OffsetDateTime now) {
        jdbcTemplate.update("""
                update chat_wb_account
                set enabled = ?, updated_at = ?
                where chat_id = ? and account_id = ?
                """,
                enabled ? 1 : 0,
                now.toString(),
                chatId,
                accountId
        );
    }

    public void disableAllForChat(long chatId, OffsetDateTime now) {
        jdbcTemplate.update("""
                update chat_wb_account
                set enabled = 0, updated_at = ?
                where chat_id = ?
                """,
                now.toString(),
                chatId
        );
    }

    public void unlink(long chatId, long accountId) {
        jdbcTemplate.update("""
                delete from chat_wb_account
                where chat_id = ? and account_id = ?
                """,
                chatId,
                accountId
        );
    }

    public int countForChat(long chatId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from chat_wb_account where chat_id = ?
                """, Integer.class, chatId);
        return count == null ? 0 : count;
    }

    private ChatLinkedWbAccount mapRow(ResultSet rs) throws SQLException {
        return new ChatLinkedWbAccount(
                rs.getLong("chat_id"),
                rs.getLong("account_id"),
                rs.getString("phone_number"),
                rs.getString("storage_state_json"),
                rs.getInt("enabled") == 1,
                rs.getString("status"),
                rs.getString("last_error"),
                OffsetDateTime.parse(rs.getString("created_at")),
                OffsetDateTime.parse(rs.getString("updated_at")),
                parseNullableOffsetDateTime(rs.getString("last_authenticated_at"))
        );
    }

    private OffsetDateTime parseNullableOffsetDateTime(String value) {
        return value == null || value.isBlank() ? null : OffsetDateTime.parse(value);
    }
}
