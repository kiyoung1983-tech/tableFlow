create extension if not exists btree_gist;

create table restaurants (
    id uuid primary key,
    name varchar(100) not null,
    status varchar(20) not null default 'ACTIVE',
    version bigint not null default 0,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint ck_restaurant_status check (status in ('ACTIVE', 'INACTIVE'))
);

create table branches (
    id uuid primary key,
    restaurant_id uuid not null references restaurants (id),
    name varchar(100) not null,
    address varchar(300) not null,
    timezone varchar(50) not null default 'Asia/Seoul',
    status varchar(20) not null default 'ACTIVE',
    slot_interval_minutes integer not null default 30,
    default_duration_minutes integer not null default 90,
    buffer_minutes integer not null default 15,
    min_advance_minutes integer not null default 60,
    booking_horizon_days integer not null default 30,
    change_cutoff_minutes integer not null default 180,
    no_show_grace_minutes integer not null default 15,
    max_party_size integer not null default 8,
    max_capacity_gap integer not null default 2,
    version bigint not null default 0,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uk_branch_restaurant_name unique (restaurant_id, name),
    constraint ck_branch_status check (status in ('ACTIVE', 'INACTIVE')),
    constraint ck_branch_slot_interval check (
        slot_interval_minutes between 5 and 120
        and mod(1440, slot_interval_minutes) = 0
    ),
    constraint ck_branch_default_duration check (default_duration_minutes between 15 and 720),
    constraint ck_branch_buffer check (buffer_minutes between 0 and 240),
    constraint ck_branch_min_advance check (min_advance_minutes between 0 and 10080),
    constraint ck_branch_booking_horizon check (booking_horizon_days between 1 and 365),
    constraint ck_branch_change_cutoff check (change_cutoff_minutes between 0 and 10080),
    constraint ck_branch_no_show_grace check (no_show_grace_minutes between 0 and 1440),
    constraint ck_branch_max_party_size check (max_party_size between 1 and 100),
    constraint ck_branch_max_capacity_gap check (max_capacity_gap between 0 and 100)
);

create index idx_branches_restaurant on branches (restaurant_id);

create table dining_tables (
    id uuid primary key,
    branch_id uuid not null references branches (id),
    name varchar(50) not null,
    capacity integer not null,
    enabled boolean not null default true,
    version bigint not null default 0,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uk_dining_table_branch_name unique (branch_id, name),
    constraint uk_dining_table_id_branch unique (id, branch_id),
    constraint ck_dining_table_capacity check (capacity between 1 and 100)
);

create index idx_dining_tables_branch_capacity
    on dining_tables (branch_id, enabled, capacity, name);

create table business_hours (
    id uuid primary key,
    branch_id uuid not null references branches (id),
    day_of_week smallint not null,
    opens_at time without time zone not null,
    closes_at time without time zone not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uk_business_hours_start unique (branch_id, day_of_week, opens_at),
    constraint ck_business_hours_day check (day_of_week between 1 and 7),
    constraint ck_business_hours_interval check (opens_at < closes_at)
);

create index idx_business_hours_branch_day
    on business_hours (branch_id, day_of_week, opens_at);

create table booking_blocks (
    id uuid primary key,
    branch_id uuid not null references branches (id),
    dining_table_id uuid,
    starts_at timestamp with time zone not null,
    ends_at timestamp with time zone not null,
    reason varchar(500) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint fk_booking_block_table_branch
        foreign key (dining_table_id, branch_id)
        references dining_tables (id, branch_id),
    constraint ck_booking_block_interval check (starts_at < ends_at)
);

create index idx_booking_blocks_branch_interval
    on booking_blocks (branch_id, starts_at, ends_at);
create index idx_booking_blocks_table_interval
    on booking_blocks (dining_table_id, starts_at, ends_at)
    where dining_table_id is not null;

create table reservations (
    id uuid primary key,
    branch_id uuid not null references branches (id),
    dining_table_id uuid not null,
    reservation_code varchar(10) not null,
    manage_token_hash varchar(64) not null,
    idempotency_key uuid not null,
    request_fingerprint varchar(64) not null,
    guest_name_ciphertext text not null,
    guest_phone_ciphertext text not null,
    contact_phone_hash varchar(64) not null,
    phone_last_four varchar(4) not null,
    party_size integer not null,
    starts_at timestamp with time zone not null,
    ends_at timestamp with time zone not null,
    occupied_until timestamp with time zone not null,
    status varchar(20) not null,
    privacy_policy_version varchar(50) not null,
    privacy_agreed_at timestamp with time zone not null,
    cancellation_reason varchar(500),
    cancelled_at timestamp with time zone,
    version bigint not null default 0,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint fk_reservation_table_branch
        foreign key (dining_table_id, branch_id)
        references dining_tables (id, branch_id),
    constraint uk_reservation_code unique (reservation_code),
    constraint uk_reservation_idempotency_key unique (idempotency_key),
    constraint ck_reservation_code check (reservation_code ~ '^[A-HJ-NP-Z2-9]{10}$'),
    constraint ck_reservation_manage_token_hash check (manage_token_hash ~ '^[0-9a-f]{64}$'),
    constraint ck_reservation_request_fingerprint check (request_fingerprint ~ '^[0-9a-f]{64}$'),
    constraint ck_reservation_contact_phone_hash check (contact_phone_hash ~ '^[0-9a-f]{64}$'),
    constraint ck_reservation_phone_last_four check (phone_last_four ~ '^[0-9]{4}$'),
    constraint ck_reservation_party_size check (party_size between 1 and 100),
    constraint ck_reservation_interval check (
        starts_at < ends_at and ends_at <= occupied_until
    ),
    constraint ck_reservation_status check (
        status in ('PENDING', 'CONFIRMED', 'SEATED', 'COMPLETED', 'CANCELLED', 'NO_SHOW')
    ),
    constraint ck_reservation_cancellation check (
        (status = 'CANCELLED' and cancelled_at is not null)
        or (status <> 'CANCELLED' and cancelled_at is null and cancellation_reason is null)
    ),
    constraint ex_reservation_table_occupancy exclude using gist (
        dining_table_id with =,
        tstzrange(starts_at, occupied_until, '[)') with &&
    ) where (status in ('PENDING', 'CONFIRMED', 'SEATED', 'COMPLETED')),
    constraint ex_reservation_customer_overlap exclude using gist (
        branch_id with =,
        contact_phone_hash with =,
        tstzrange(starts_at, ends_at, '[)') with &&
    ) where (status in ('PENDING', 'CONFIRMED', 'SEATED', 'COMPLETED'))
);

create index idx_reservations_branch_start_status
    on reservations (branch_id, starts_at, status);
create index idx_reservations_table_start
    on reservations (dining_table_id, starts_at);

create table reservation_status_history (
    id uuid primary key,
    reservation_id uuid not null references reservations (id),
    from_status varchar(20),
    to_status varchar(20) not null,
    actor_type varchar(20) not null,
    actor_id varchar(100),
    reason varchar(500),
    changed_at timestamp with time zone not null,
    constraint ck_reservation_history_from_status check (
        from_status is null
        or from_status in ('PENDING', 'CONFIRMED', 'SEATED', 'COMPLETED', 'CANCELLED', 'NO_SHOW')
    ),
    constraint ck_reservation_history_to_status check (
        to_status in ('PENDING', 'CONFIRMED', 'SEATED', 'COMPLETED', 'CANCELLED', 'NO_SHOW')
    ),
    constraint ck_reservation_history_actor check (
        actor_type in ('CUSTOMER', 'ADMIN', 'SYSTEM')
    )
);

create index idx_reservation_history_reservation_changed
    on reservation_status_history (reservation_id, changed_at);
