-- What menD paused itself for, so a credential failure pauses once rather than every tick: an
-- operator who resumes with the key still broken is not overruled fifteen seconds later, while a
-- different failure — or the same one after it cleared — pauses again.

alter table engine_pause add column auto_trigger varchar(1024);
