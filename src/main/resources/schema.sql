create table if not exists chat_subscription (
    chat_id integer primary key,
    user_id integer,
    title text,
    chat_type text not null,
    active integer not null default 1,
    created_at text not null,
    last_seen_at text not null
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
