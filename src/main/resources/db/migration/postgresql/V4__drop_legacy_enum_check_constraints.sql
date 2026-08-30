-- ddl-auto pinned the enum values it knew about in a CHECK constraint per enum-backed column, and
-- V3 carries those constraints across the type change. On an adopted database that makes adding a
-- state a schema change and a new value (UNVERIFIED, CHANGES_REQUESTED) fails to insert; V1
-- deliberately declares no such constraint. Hibernate generates the names, so the constraints are
-- found through the catalog: every single-column CHECK on an enum-backed column, nothing else.
-- On a database created by V1 there are none and this is a no-op.
do $$
declare
    target record;
    doomed text;
begin
    for target in
        select * from (values
            ('remediation_task', 'state'),
            ('remediation_task', 'verification_tier'),
            ('task_event', 'from_state'),
            ('task_event', 'to_state'),
            ('repository', 'access_state'),
            ('repository', 'index_state'),
            ('repository_context', 'kind'),
            ('learning', 'scope'),
            ('learning', 'status'),
            ('learning', 'recommended_action')
        ) as t(table_name, column_name)
    loop
        for doomed in
            select c.conname
            from pg_constraint c
            join pg_class rel on rel.oid = c.conrelid
            join pg_namespace n on n.oid = rel.relnamespace
            join pg_attribute a on a.attrelid = c.conrelid and a.attnum = c.conkey[1]
            where c.contype = 'c'
              and n.nspname = current_schema()
              and rel.relname = target.table_name
              and a.attname = target.column_name
              and array_length(c.conkey, 1) = 1
        loop
            execute format('alter table %I drop constraint %I', target.table_name, doomed);
        end loop;
    end loop;
end $$;
