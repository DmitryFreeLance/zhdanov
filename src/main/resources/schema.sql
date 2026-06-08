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
    call_enabled integer not null default 0,
    phone_number text,
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
