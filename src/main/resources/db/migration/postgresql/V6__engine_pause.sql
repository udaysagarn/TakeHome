-- Whether an operator has paused menD. One row, like devin_credential: pausing is a property of the
-- instance, every replica has to observe it, and it has to survive a restart — an in-memory flag
-- would resume the spend the moment the container came back.

create table engine_pause (
    id         bigint primary key,
    version    bigint,
    paused     boolean not null,
    reason     varchar(1024),
    actor      varchar(255),
    changed_at timestamp(6) with time zone
);
