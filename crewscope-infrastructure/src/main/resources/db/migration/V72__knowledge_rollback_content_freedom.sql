-- M13b-A05: knowledge rollback mints the target's verbatim content as the next
-- immutable revision, so equal digests across revisions of one entry are legal by
-- design — the same stance team_skill_version took in V61 (ADR-031 §4, which
-- deliberately ships no content-hash uniqueness). V52 added this constraint as the
-- no-op-publish guard before rollback existed; that guard now lives in the command
-- layer (publish refuses a draft whose digest equals the effective version's).
ALTER TABLE crewscope.knowledge_entry_version
    DROP CONSTRAINT uk_knowledge_entry_version_content;
