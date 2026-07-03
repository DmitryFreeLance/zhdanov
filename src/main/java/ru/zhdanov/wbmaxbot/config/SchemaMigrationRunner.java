package ru.zhdanov.wbmaxbot.config;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class SchemaMigrationRunner {

    private final JdbcTemplate jdbcTemplate;

    public SchemaMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void migrate() {
        ensureChatSubscriptionColumns();
        ensureVoiceCallAttemptTable();
    }

    private void ensureChatSubscriptionColumns() {
        Set<String> columns = loadColumnNames("chat_subscription");
        addColumnIfMissing(columns, "auto_report_enabled", "alter table chat_subscription add column auto_report_enabled integer not null default 1");
        addColumnIfMissing(columns, "report_interval_minutes", "alter table chat_subscription add column report_interval_minutes integer not null default 15");
        addColumnIfMissing(columns, "last_report_sent_at", "alter table chat_subscription add column last_report_sent_at text");
        addColumnIfMissing(columns, "shk_threshold", "alter table chat_subscription add column shk_threshold integer default 1200");
        addColumnIfMissing(columns, "ratio_threshold", "alter table chat_subscription add column ratio_threshold real default 0.8");
        addColumnIfMissing(columns, "alert_parking", "alter table chat_subscription add column alert_parking text");
        jdbcTemplate.update("""
                update chat_subscription
                set ratio_threshold = 0.8
                where ratio_threshold = 0.9
                """);
        addColumnIfMissing(columns, "call_enabled", "alter table chat_subscription add column call_enabled integer not null default 0");
        addColumnIfMissing(columns, "phone_number", "alter table chat_subscription add column phone_number text");
        addColumnIfMissing(columns, "call_time_window_start", "alter table chat_subscription add column call_time_window_start text");
        addColumnIfMissing(columns, "call_time_window_end", "alter table chat_subscription add column call_time_window_end text");
        addColumnIfMissing(columns, "call_max_daily_attempts", "alter table chat_subscription add column call_max_daily_attempts integer not null default 5");
        addColumnIfMissing(columns, "call_answer_cooldown_minutes", "alter table chat_subscription add column call_answer_cooldown_minutes integer not null default 300");
        addColumnIfMissing(columns, "last_call_answered_at", "alter table chat_subscription add column last_call_answered_at text");
        addColumnIfMissing(columns, "pending_input_state", "alter table chat_subscription add column pending_input_state text");
        addColumnIfMissing(columns, "pending_wb_auth_flow_id", "alter table chat_subscription add column pending_wb_auth_flow_id text");
        addColumnIfMissing(columns, "pending_wb_auth_phone_number", "alter table chat_subscription add column pending_wb_auth_phone_number text");
        jdbcTemplate.update("""
                update chat_subscription
                set call_max_daily_attempts = 5
                where call_max_daily_attempts is null
                   or call_max_daily_attempts <= 0
                   or call_max_daily_attempts > 5
                """);
        jdbcTemplate.update("""
                update chat_subscription
                set call_answer_cooldown_minutes = 300
                where call_answer_cooldown_minutes is null
                   or call_answer_cooldown_minutes <= 0
                """);
    }

    private void ensureVoiceCallAttemptTable() {
        jdbcTemplate.execute("""
                create table if not exists voice_call_attempt (
                    id integer primary key autoincrement,
                    created_at text not null,
                    updated_at text not null,
                    chat_id integer not null,
                    account_id integer not null,
                    trigger_type text not null,
                    phone_number text,
                    provider text,
                    external_id text,
                    status text not null,
                    call_started integer not null default 0,
                    answered_by_human integer not null default 0,
                    details text
                )
                """);
        jdbcTemplate.execute("""
                create index if not exists idx_voice_call_attempt_account_created_at
                on voice_call_attempt(account_id, created_at)
                """);
        jdbcTemplate.execute("""
                create index if not exists idx_voice_call_attempt_chat_account_created_at
                on voice_call_attempt(chat_id, account_id, created_at)
                """);
    }

    private Set<String> loadColumnNames(String tableName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("pragma table_info(" + tableName + ")");
        Set<String> names = new HashSet<>();
        for (Map<String, Object> row : rows) {
            Object name = row.get("name");
            if (name != null) {
                names.add(String.valueOf(name));
            }
        }
        return names;
    }

    private void addColumnIfMissing(Set<String> columns, String columnName, String sql) {
        if (columns.contains(columnName)) {
            return;
        }
        jdbcTemplate.execute(sql);
        columns.add(columnName);
    }
}
