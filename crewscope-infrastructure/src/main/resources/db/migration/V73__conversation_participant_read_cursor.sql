-- M13b-A07: per-participant read cursor for the conversation unread projection.
-- One added column with a default, no backfill: 0 means "no read receipt yet",
-- which renders every existing message unread until the viewer posts one.
-- The migration number is V73, not the V71 the plan sketched: V72 (A05) was
-- already applied everywhere when this landed, and inserting a lower-numbered
-- migration would strand every existing database on Flyway's pending check
-- (out-of-order is disabled by design), so V71 stays a permanent gap.
ALTER TABLE crewscope.conversation_participant
    ADD COLUMN last_read_sequence BIGINT NOT NULL DEFAULT 0;
