create table event_publication (
    id uuid primary key,
    publication_date timestamp with time zone not null,
    listener_id varchar(255) not null,
    serialized_event varchar(255) not null,
    event_type varchar(255) not null,
    completion_date timestamp with time zone,
    last_resubmission_date timestamp with time zone,
    completion_attempts integer not null default 0,
    status varchar(255)
);

create index idx_event_publication_completion_date
    on event_publication (completion_date);
