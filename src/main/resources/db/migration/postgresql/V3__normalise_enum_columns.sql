-- A database created before the migrations existed was built by Hibernate's ddl-auto, whose enum
-- mapping does not match what ddl-auto=validate expects once the schema is owned by migrations.
-- Normalise the enum-backed columns to the varchar widths the baseline declares; on a database
-- created by V1 this is a no-op.

alter table remediation_task alter column state type varchar(32) using state::text;
alter table remediation_task alter column verification_tier type varchar(32) using verification_tier::text;

alter table task_event alter column from_state type varchar(32) using from_state::text;
alter table task_event alter column to_state type varchar(32) using to_state::text;

alter table repository alter column access_state type varchar(32) using access_state::text;
alter table repository alter column index_state type varchar(32) using index_state::text;

alter table repository_context alter column kind type varchar(32) using kind::text;

alter table learning alter column scope type varchar(16) using scope::text;
alter table learning alter column status type varchar(16) using status::text;
alter table learning alter column recommended_action type varchar(32) using recommended_action::text;
