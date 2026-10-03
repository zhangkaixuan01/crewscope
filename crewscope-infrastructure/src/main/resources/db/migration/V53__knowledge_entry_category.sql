-- M10-A02a: classify knowledge entries with the frozen five-value taxonomy (D1).
-- Existing heads default to OTHER so the backfill needs no data migration; the CHECK
-- closes the value set at the database boundary exactly like the domain enum, so a
-- drifted writer is rejected before persistence instead of poisoning the listing
-- filters. The category is head state (mutable through updateDraft, guarded by the
-- head's optimistic lock) and deliberately excluded from the version content hash.

ALTER TABLE crewscope.knowledge_entry
    ADD COLUMN category VARCHAR(16) NOT NULL DEFAULT 'OTHER';

ALTER TABLE crewscope.knowledge_entry
    ADD CONSTRAINT ck_knowledge_entry_category
        CHECK (category IN ('CONVENTION', 'RUNBOOK', 'DECISION', 'GUIDE', 'OTHER'));
