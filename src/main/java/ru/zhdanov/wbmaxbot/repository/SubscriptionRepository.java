package ru.zhdanov.wbmaxbot.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.zhdanov.wbmaxbot.config.AppProperties;
import ru.zhdanov.wbmaxbot.model.ChatSubscription;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class SubscriptionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final AppProperties properties;

    public SubscriptionRepository(JdbcTemplate jdbcTemplate, AppProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public void upsert(long chatId, Long userId, String title, String chatType, boolean active, OffsetDateTime now) {
        jdbcTemplate.update("""
                insert into chat_subscription(
                    chat_id, user_id, title, chat_type, active,
                    auto_report_enabled, report_interval_minutes, shk_threshold, ratio_threshold, alert_parking, call_enabled,
                    call_time_window_start, call_time_window_end, call_max_daily_attempts, call_answer_cooldown_minutes,
                    created_at, last_seen_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                1,
                15,
                properties.getAlert().getShkThreshold(),
                properties.getAlert().getRatioThreshold(),
                null,
                0,
                null,
                null,
                5,
                300,
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
                select chat_id, user_id, title, chat_type, active,
                       auto_report_enabled, report_interval_minutes, last_report_sent_at,
                       shk_threshold, ratio_threshold, alert_parking, call_enabled, phone_number,
                       call_time_window_start, call_time_window_end, call_max_daily_attempts, call_answer_cooldown_minutes,
                       last_call_answered_at, pending_input_state,
                       pending_wb_auth_flow_id, pending_wb_auth_phone_number,
                       created_at, last_seen_at
                from chat_subscription
                where active = 1
                order by created_at
                """,
                (rs, rowNum) -> mapRow(rs)
        );
    }

    public Optional<ChatSubscription> findByChatId(long chatId) {
        List<ChatSubscription> rows = jdbcTemplate.query("""
                select chat_id, user_id, title, chat_type, active,
                       auto_report_enabled, report_interval_minutes, last_report_sent_at,
                       shk_threshold, ratio_threshold, alert_parking, call_enabled, phone_number,
                       call_time_window_start, call_time_window_end, call_max_daily_attempts, call_answer_cooldown_minutes,
                       last_call_answered_at, pending_input_state,
                       pending_wb_auth_flow_id, pending_wb_auth_phone_number,
                       created_at, last_seen_at
                from chat_subscription
                where chat_id = ?
                """,
                (rs, rowNum) -> mapRow(rs),
                chatId
        );
        return rows.stream().findFirst();
    }

    public void updateReportSchedule(long chatId, boolean enabled, int intervalMinutes) {
        jdbcTemplate.update("""
                update chat_subscription
                set auto_report_enabled = ?, report_interval_minutes = ?
                where chat_id = ?
                """,
                enabled ? 1 : 0,
                intervalMinutes,
                chatId
        );
    }

    public void markReportSent(long chatId, OffsetDateTime sentAt) {
        jdbcTemplate.update("""
                update chat_subscription
                set last_report_sent_at = ?, last_seen_at = ?
                where chat_id = ?
                """,
                sentAt.toString(),
                sentAt.toString(),
                chatId
        );
    }

    public void updateShkThreshold(long chatId, Integer threshold) {
        jdbcTemplate.update("""
                update chat_subscription
                set shk_threshold = ?
                where chat_id = ?
                """,
                threshold,
                chatId
        );
    }

    public void updateRatioThreshold(long chatId, Double threshold) {
        jdbcTemplate.update("""
                update chat_subscription
                set ratio_threshold = ?
                where chat_id = ?
                """,
                threshold,
                chatId
        );
    }

    public void updateAlertParking(long chatId, String alertParking) {
        jdbcTemplate.update("""
                update chat_subscription
                set alert_parking = ?
                where chat_id = ?
                """,
                alertParking,
                chatId
        );
    }

    public void updateCallEnabled(long chatId, boolean enabled) {
        jdbcTemplate.update("""
                update chat_subscription
                set call_enabled = ?
                where chat_id = ?
                """,
                enabled ? 1 : 0,
                chatId
        );
    }

    public void updatePhoneNumber(long chatId, String phoneNumber) {
        jdbcTemplate.update("""
                update chat_subscription
                set phone_number = ?
                where chat_id = ?
                """,
                phoneNumber,
                chatId
        );
    }

    public void updateCallTimeWindow(long chatId, String start, String end) {
        jdbcTemplate.update("""
                update chat_subscription
                set call_time_window_start = ?,
                    call_time_window_end = ?
                where chat_id = ?
                """,
                start,
                end,
                chatId
        );
    }

    public void updateCallMaxDailyAttempts(long chatId, int maxDailyAttempts) {
        jdbcTemplate.update("""
                update chat_subscription
                set call_max_daily_attempts = ?
                where chat_id = ?
                """,
                maxDailyAttempts,
                chatId
        );
    }

    public void updateCallAnswerCooldownMinutes(long chatId, int cooldownMinutes) {
        jdbcTemplate.update("""
                update chat_subscription
                set call_answer_cooldown_minutes = ?
                where chat_id = ?
                """,
                cooldownMinutes,
                chatId
        );
    }

    public void updateLastCallAnsweredAt(long chatId, OffsetDateTime answeredAt) {
        jdbcTemplate.update("""
                update chat_subscription
                set last_call_answered_at = ?
                where chat_id = ?
                """,
                answeredAt == null ? null : answeredAt.toString(),
                chatId
        );
    }

    public void updatePendingInputState(long chatId, String pendingInputState) {
        jdbcTemplate.update("""
                update chat_subscription
                set pending_input_state = ?
                where chat_id = ?
                """,
                pendingInputState,
                chatId
        );
    }

    public void updatePendingWbAuth(long chatId, String pendingInputState, String flowId, String phoneNumber) {
        jdbcTemplate.update("""
                update chat_subscription
                set pending_input_state = ?,
                    pending_wb_auth_flow_id = ?,
                    pending_wb_auth_phone_number = ?
                where chat_id = ?
                """,
                pendingInputState,
                flowId,
                phoneNumber,
                chatId
        );
    }

    public int countActive() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from chat_subscription where active = 1
                """, Integer.class);
        return count == null ? 0 : count;
    }

    private ChatSubscription mapRow(ResultSet rs) throws SQLException {
        Number userIdValue = (Number) rs.getObject("user_id");
        Number shkThresholdValue = (Number) rs.getObject("shk_threshold");
        Number ratioThresholdValue = (Number) rs.getObject("ratio_threshold");
        return new ChatSubscription(
                rs.getLong("chat_id"),
                userIdValue == null ? null : userIdValue.longValue(),
                rs.getString("title"),
                rs.getString("chat_type"),
                rs.getInt("active") == 1,
                rs.getInt("auto_report_enabled") == 1,
                rs.getInt("report_interval_minutes"),
                parseNullableOffsetDateTime(rs.getString("last_report_sent_at")),
                shkThresholdValue == null ? null : shkThresholdValue.intValue(),
                ratioThresholdValue == null ? null : ratioThresholdValue.doubleValue(),
                rs.getString("alert_parking"),
                rs.getInt("call_enabled") == 1,
                rs.getString("phone_number"),
                rs.getString("call_time_window_start"),
                rs.getString("call_time_window_end"),
                rs.getInt("call_max_daily_attempts"),
                rs.getInt("call_answer_cooldown_minutes"),
                parseNullableOffsetDateTime(rs.getString("last_call_answered_at")),
                rs.getString("pending_input_state"),
                rs.getString("pending_wb_auth_flow_id"),
                rs.getString("pending_wb_auth_phone_number"),
                DbTimeParser.parseRequired(rs.getString("created_at")),
                DbTimeParser.parseRequired(rs.getString("last_seen_at"))
        );
    }

    private OffsetDateTime parseNullableOffsetDateTime(String value) {
        return DbTimeParser.parseNullable(value);
    }
}
