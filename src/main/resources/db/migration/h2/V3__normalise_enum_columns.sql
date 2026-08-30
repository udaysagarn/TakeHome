-- A database created before the migrations existed was built by Hibernate's ddl-auto, which emits a
-- native ENUM column for every @Enumerated field. Adopting such a database at the baseline leaves
-- those columns as ENUM, and ddl-auto=validate then refuses to start against them. Normalise them to
-- the varchar widths the baseline declares; on a database created by V1 this is a no-op.

alter table remediation_task alter column state set data type varchar(32);
alter table remediation_task alter column verification_tier set data type varchar(32);

alter table task_event alter column from_state set data type varchar(32);
alter table task_event alter column to_state set data type varchar(32);

alter table repository alter column access_state set data type varchar(32);
alter table repository alter column index_state set data type varchar(32);

alter table repository_context alter column kind set data type varchar(32);

alter table learning alter column scope set data type varchar(16);
alter table learning alter column status set data type varchar(16);
alter table learning alter column recommended_action set data type varchar(32);
