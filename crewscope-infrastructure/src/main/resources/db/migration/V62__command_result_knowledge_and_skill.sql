-- M10 A02/A03: knowledge entry and team skill commands store their own team-scoped
-- coordinates (team_id + resource id, no project scope), so an unknown-outcome retry
-- replays the committed head through command-results instead of re-creating it. The
-- V40 whitelist predates both aggregate types and rejected their receipts with a check
-- violation — caught by the F01a real-stack spec, invisible to the repository-tier tests.
ALTER TABLE crewscope.command_result DROP CONSTRAINT ck_command_result_coordinate;
ALTER TABLE crewscope.command_result ADD CONSTRAINT ck_command_result_coordinate CHECK (
    (resource_type = 'WORK_PROJECT' AND project_id IS NOT NULL AND project_id = resource_id)
    OR (resource_type = 'WORK_ITEM' AND project_id IS NOT NULL)
    OR (resource_type = 'TASK' AND project_id IS NOT NULL)
    OR (resource_type = 'CONVERSATION' AND project_id IS NULL)
    OR (resource_type = 'TEAM_MEMBER' AND project_id IS NULL)
    OR (resource_type = 'KNOWLEDGE_ENTRY' AND project_id IS NULL)
    OR (resource_type = 'TEAM_SKILL' AND project_id IS NULL)
);
