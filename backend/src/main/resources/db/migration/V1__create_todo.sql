create table todos (
    id uuid primary key,
    title varchar(200) not null,
    completed boolean not null default false,
    created_at timestamp with time zone not null
);
create index idx_todos_created_at on todos (created_at desc);
