-- The verdict on menD's Devin credential, so a refused key is a sentence on the dashboard rather
-- than a board that quietly never advances. One row: the credential is a property of the instance,
-- and every replica shares it, so an in-memory flag would only be known to the replica refused.

create table devin_credential (
    id          bigint primary key,
    version     bigint,
    usable      boolean not null,
    reason      varchar(1024),
    checked_at  timestamp(6) with time zone
);
