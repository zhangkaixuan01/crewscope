-- A line comment has more than one command in its lifetime (one create, then any number of edits and
-- deletes), but its receipt was stored in a single column that every update overwrote. Once an edit
-- moved the key, the create key was free again: a client retrying its original create no longer
-- matched a receipt, fell through the replay branch, and the unique index no longer blocked it, so
-- the comment was written a second time.
--
-- The create key therefore becomes immutable and the update command gets its own slot. The create
-- receipt stays resolvable for as long as the comment exists, at the cost of remembering only the
-- newest update command; replaying a superseded update is a genuine version conflict, which the
-- command service already reports as such.
ALTER TABLE crewscope.review_line_comment
    ADD COLUMN last_command_idempotency_key VARCHAR(200);

ALTER TABLE crewscope.review_line_comment
    ADD CONSTRAINT ck_review_line_comment_last_command_key CHECK (
        last_command_idempotency_key IS NULL
        OR last_command_idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}$'
    );

-- Partial: the column is null until the first edit, and many comments share that state.
CREATE UNIQUE INDEX uk_review_line_comment_last_command_key
    ON crewscope.review_line_comment (organization_id, last_command_idempotency_key)
    WHERE last_command_idempotency_key IS NOT NULL;
