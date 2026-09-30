-- M9b-Q02: a TaskExecution's policy snapshots now record two independent revision chains —
-- the executor's supersede chain and the advisory reviewer's per-attempt snapshot (the review
-- path M5 designed but never exercised). The original (task_execution_id, revision) unique
-- constraint assumed a single chain, so the first reviewer snapshot collided with the
-- execution's revision 1. Revisions stay unique per captured responsibility seat: the executor
-- chain pins one EXECUTOR assignment for the whole attempt and the reviewer snapshot pins its
-- own REVIEWER assignment, so widening the key with execution_assignment_id preserves the
-- per-chain guarantee while letting both chains start at revision 1.
ALTER TABLE crewscope.policy_snapshot DROP CONSTRAINT uk_policy_snapshot_revision;

ALTER TABLE crewscope.policy_snapshot ADD CONSTRAINT uk_policy_snapshot_revision
    UNIQUE (task_execution_id, execution_assignment_id, revision);
