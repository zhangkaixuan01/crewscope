-- A07 R29: an invitation acceptance stores its own TEAM_MEMBER coordinate
-- (team_id + member_id, no project scope) so an accept replay lands on the
-- exact membership instead of the client guessing among same-named Teams.
ALTER TABLE crewscope.command_result DROP CONSTRAINT ck_command_result_coordinate;
ALTER TABLE crewscope.command_result ADD CONSTRAINT ck_command_result_coordinate CHECK (
    (resource_type = 'WORK_PROJECT' AND project_id IS NOT NULL AND project_id = resource_id)
    OR (resource_type = 'WORK_ITEM' AND project_id IS NOT NULL)
    OR (resource_type = 'CONVERSATION' AND project_id IS NULL)
    OR (resource_type = 'TEAM_MEMBER' AND project_id IS NULL)
);
