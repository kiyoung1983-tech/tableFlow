alter table reservations
    add column personal_data_erased_at timestamp with time zone;

create index idx_reservations_privacy_erasure_candidates
    on reservations (ends_at)
    where personal_data_erased_at is null
      and status in ('COMPLETED', 'CANCELLED', 'NO_SHOW');
