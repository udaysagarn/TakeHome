-- Cross-process ownership of a repository profile, mirroring the lease columns on
-- remediation_task. Without it two replicas both start a profiling session for the same
-- repository, because the session is created before its id is persisted.

alter table repository add column owner_id varchar(128);
alter table repository add column lease_acquired_at timestamp(6) with time zone;
alter table repository add column lease_expires_at timestamp(6) with time zone;
alter table repository add column lease_takeovers integer default 0 not null;
