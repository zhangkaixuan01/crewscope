-- A Coding Specialist is an isolated runtime role. It executes with the Principal and profile
-- already pinned by the Task PolicySnapshot, including Personal and Team Agent delegation.
ALTER TABLE crewscope.agent_runtime_session
    DROP CONSTRAINT ck_agent_runtime_session_shape;

ALTER TABLE crewscope.agent_runtime_session
    ADD CONSTRAINT ck_agent_runtime_session_shape CHECK (
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
    );
