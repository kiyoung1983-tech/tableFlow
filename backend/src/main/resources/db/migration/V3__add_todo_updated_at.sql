alter table todos add column updated_at timestamp with time zone;

update todos
set updated_at = created_at
where updated_at is null;

alter table todos alter column updated_at set not null;
