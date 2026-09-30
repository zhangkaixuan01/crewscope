-- M9b-Q02: the advisory reviewer executes as its own attempt-scoped, step-less session.
-- M5 designed the reviewer call around a Specialist session, but the domain shape requires a
-- SPECIALIST session to carry a step the reviewer never has — the first real review execution
-- could therefore never find its session. The new REVIEW purpose gives the reviewer a first-
-- class session shape; the persisted-purpose check widens to accept it.
ALTER TABLE crewscope.agent_runtime_session DROP CONSTRAINT ck_agent_runtime_session_purpose;

ALTER TABLE crewscope.agent_runtime_session ADD CONSTRAINT ck_agent_runtime_session_purpose
    CHECK (session_purpose = ANY (ARRAY[
        'PERSONAL', 'TASK', 'STEP', 'SPECIALIST', 'REVIEW'
    ]::text[]));

-- The shape check carries the same per-purpose column fingerprint and was last rebuilt by V17
-- for the four purposes it knew; without a REVIEW branch the first real reviewer session is
-- rejected by the database even though the domain accepted it.
ALTER TABLE crewscope.agent_runtime_session DROP CONSTRAINT ck_agent_runtime_session_shape;

ALTER TABLE crewscope.agent_runtime_session ADD CONSTRAINT ck_agent_runtime_session_shape CHECK (
    (session_purpose = 'PERSONAL'
        AND conversation_id IS NOT NULL
        AND owner_member_id IS NOT NULL
        AND owner_principal_id IS NOT NULL
        AND personal_agent_principal_id = agent_principal_id
        AND agent_principal_type = 'PERSONAL_AGENT'
        AND agent_profile_type = 'PERSONAL'
        AND project_id IS NULL
        AND task_id IS NULL
        AND task_execution_id IS NULL
        AND step_execution_id IS NULL)
    OR (session_purpose = 'TASK'
        AND conversation_id IS NULL
        AND owner_member_id IS NULL
        AND owner_principal_id IS NULL
        AND personal_agent_principal_id IS NULL
        AND ((agent_principal_type = 'PERSONAL_AGENT' AND agent_profile_type = 'PERSONAL')
            OR (agent_principal_type = 'TEAM_AGENT' AND agent_profile_type = 'TEAM'))
        AND project_id IS NOT NULL
        AND task_id IS NOT NULL
        AND task_execution_id IS NOT NULL
        AND step_execution_id IS NULL)
    OR (session_purpose = 'STEP'
        AND conversation_id IS NULL
        AND owner_member_id IS NULL
        AND owner_principal_id IS NULL
        AND personal_agent_principal_id IS NULL
        AND agent_principal_type = 'TEAM_AGENT'
        AND agent_profile_type = 'TEAM'
        AND project_id IS NOT NULL
        AND task_id IS NOT NULL
        AND task_execution_id IS NOT NULL
        AND step_execution_id IS NOT NULL)
    OR (session_purpose = 'SPECIALIST'
        AND conversation_id IS NULL
        AND owner_member_id IS NULL
        AND owner_principal_id IS NULL
        AND personal_agent_principal_id IS NULL
        AND ((agent_principal_type = 'PERSONAL_AGENT' AND agent_profile_type = 'PERSONAL')
            OR (agent_principal_type = 'TEAM_AGENT' AND agent_profile_type = 'TEAM')
            OR (agent_principal_type = 'SPECIALIST_AGENT' AND agent_profile_type = 'SPECIALIST'))
        AND project_id IS NOT NULL
        AND task_id IS NOT NULL
        AND task_execution_id IS NOT NULL
        AND step_execution_id IS NOT NULL)
    OR (session_purpose = 'REVIEW'
        AND conversation_id IS NULL
        AND owner_member_id IS NULL
        AND owner_principal_id IS NULL
        AND personal_agent_principal_id IS NULL
        AND ((agent_principal_type = 'PERSONAL_AGENT' AND agent_profile_type = 'PERSONAL')
            OR (agent_principal_type = 'TEAM_AGENT' AND agent_profile_type = 'TEAM')
            OR (agent_principal_type = 'SPECIALIST_AGENT' AND agent_profile_type = 'SPECIALIST'))
        AND project_id IS NOT NULL
        AND task_id IS NOT NULL
        AND task_execution_id IS NOT NULL
        AND step_execution_id IS NULL)
);
