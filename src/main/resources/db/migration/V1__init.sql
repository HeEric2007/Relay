create table endpoints (
    id          uuid        primary key default gen_random_uuid(),
    url         text        not null,
    secret      text        not null,
    event_types text[]      not null default '{}',
    active      boolean     not null default true,
    created_at  timestamptz not null default now()
);

create table events (
    id         uuid        primary key default gen_random_uuid(),
    type       text        not null,
    payload    jsonb       not null,
    created_at timestamptz not null default now()
);

create table deliveries (
    id               uuid        primary key default gen_random_uuid(),
    event_id         uuid        not null references events (id),
    endpoint_id      uuid        not null references endpoints (id),
    status           text        not null default 'pending'
                                 check (status in ('pending', 'delivering', 'delivered', 'dead')),
    attempts         int         not null default 0,
    run_at           timestamptz not null default now(),
    locked_at        timestamptz,
    locked_by        text,
    last_status_code int,
    last_error       text,
    created_at       timestamptz not null default now()
);

create index deliveries_claim_idx on deliveries (run_at) where status = 'pending';
create index deliveries_stuck_idx on deliveries (locked_at) where status = 'delivering';
create index deliveries_endpoint_idx on deliveries (endpoint_id, created_at desc);
