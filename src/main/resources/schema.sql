create table if not exists chat_subscription (
    chat_id integer primary key,
    user_id integer,
    title text,
    chat_type text not null,
    active integer not null default 1,
    auto_report_enabled integer not null default 1,
    report_interval_minutes integer not null default 15,
    last_report_sent_at text,
    shk_threshold integer default 1200,
    ratio_threshold real default 0.8,
    alert_parking text,
    call_enabled integer not null default 0,
    phone_number text,
    call_time_window_start text,
    call_time_window_end text,
    call_max_daily_attempts integer not null default 5,
    call_answer_cooldown_minutes integer not null default 300,
    last_call_answered_at text,
    pending_input_state text,
    pending_wb_auth_flow_id text,
    pending_wb_auth_phone_number text,
    created_at text not null,
    last_seen_at text not null
);

create table if not exists wb_account (
    id integer primary key autoincrement,
    phone_number text not null unique,
    storage_state_json text not null,
    status text not null default 'CONNECTED',
    last_error text,
    created_at text not null,
    updated_at text not null,
    last_authenticated_at text
);

create table if not exists chat_wb_account (
    chat_id integer not null,
    account_id integer not null,
    enabled integer not null default 1,
    created_at text not null,
    updated_at text not null,
    primary key (chat_id, account_id),
    foreign key (chat_id) references chat_subscription(chat_id),
    foreign key (account_id) references wb_account(id)
);

create table if not exists report_run (
    id integer primary key autoincrement,
    scraped_at text not null,
    source text not null,
    status text not null,
    summary_json text not null,
    message_text text,
    error_message text
);

create table if not exists report_row_snapshot (
    id integer primary key autoincrement,
    run_id integer not null,
    lo_name text,
    auto_requests text,
    pickup_time text,
    route text,
    parking text,
    boxes integer,
    kgt integer,
    shk integer,
    norm integer,
    ratio real,
    volume_liters real,
    avg_accumulation_liters real,
    distance_km real,
    raw_json text,
    foreign key (run_id) references report_run(id)
);

create table if not exists alert_event (
    id integer primary key autoincrement,
    created_at text not null,
    dedupe_key text not null,
    route text,
    parking text,
    shk integer,
    norm integer,
    ratio real,
    reason text not null,
    message_status text not null,
    voice_provider text,
    voice_status text,
    voice_external_id text,
    details text
);

create index if not exists idx_alert_event_dedupe_key_created_at
    on alert_event(dedupe_key, created_at);

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
);

create index if not exists idx_voice_call_attempt_account_created_at
    on voice_call_attempt(account_id, created_at);

create index if not exists idx_voice_call_attempt_chat_account_created_at
    on voice_call_attempt(chat_id, account_id, created_at);
