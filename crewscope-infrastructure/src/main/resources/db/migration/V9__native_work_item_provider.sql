-- Register the product-owned NativeWorkItem Provider for every Organization that already has a
-- complete active Team. New and completed legacy Teams use the same stable IDs in application code.
WITH ready_team AS (
    SELECT
        team.organization_id,
        team.id AS team_id,
        team.default_workspace_id AS workspace_id,
        owner_member.user_principal_id AS actor_principal_id
    FROM crewscope.team team
    JOIN crewscope.workspace workspace
      ON workspace.organization_id = team.organization_id
     AND workspace.team_id = team.id
     AND workspace.id = team.default_workspace_id
    JOIN crewscope.team_member owner_member
      ON owner_member.organization_id = team.organization_id
     AND owner_member.team_id = team.id
     AND owner_member.id = team.owner_member_id
    WHERE team.status = 'ACTIVE'
      AND team.owner_member_id IS NOT NULL
      AND team.default_workspace_id IS NOT NULL
      AND workspace.workspace_type = 'TEAM'
      AND workspace.status = 'ACTIVE'
), organization_actor AS (
    SELECT DISTINCT ON (organization_id)
        organization_id,
        actor_principal_id
    FROM ready_team
    ORDER BY organization_id, team_id
)
INSERT INTO crewscope.provider_definition (
    id, organization_id, provider_key, provider_type,
    interface_version, display_name, capabilities, status, version,
    created_at, created_by_principal_id, updated_at, updated_by_principal_id
)
SELECT
    md5(
        'crewscope:built-in-provider:definition:v1:work-item:'
        || organization_id::TEXT
    )::UUID,
    organization_id,
    'work-item',
    'WORK_ITEM',
    '1.0.0',
    'CrewScope WorkItem',
    '["workitem.comment","workitem.create","workitem.read","workitem.resource-link","workitem.update"]'::JSONB,
    'ACTIVE',
    0,
    CURRENT_TIMESTAMP,
    actor_principal_id,
    CURRENT_TIMESTAMP,
    actor_principal_id
FROM organization_actor
ON CONFLICT (organization_id, provider_key) DO NOTHING;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM crewscope.provider_definition definition
        JOIN (
            SELECT DISTINCT team.organization_id
            FROM crewscope.team team
            JOIN crewscope.workspace workspace
              ON workspace.organization_id = team.organization_id
             AND workspace.team_id = team.id
             AND workspace.id = team.default_workspace_id
            JOIN crewscope.team_member owner_member
              ON owner_member.organization_id = team.organization_id
             AND owner_member.team_id = team.id
             AND owner_member.id = team.owner_member_id
            WHERE team.status = 'ACTIVE'
              AND workspace.workspace_type = 'TEAM'
              AND workspace.status = 'ACTIVE'
        ) ready_organization USING (organization_id)
        WHERE definition.provider_key = 'work-item'
          AND (
              definition.id <> md5(
                  'crewscope:built-in-provider:definition:v1:work-item:'
                  || definition.organization_id::TEXT
              )::UUID
              OR definition.provider_type <> 'WORK_ITEM'
              OR definition.interface_version <> '1.0.0'
              OR definition.display_name <> 'CrewScope WorkItem'
              OR JSONB_ARRAY_LENGTH(definition.capabilities) <> 5
              OR NOT definition.capabilities @>
                  '["workitem.comment","workitem.create","workitem.read","workitem.resource-link","workitem.update"]'::JSONB
          )
    ) THEN
        RAISE EXCEPTION 'NativeWorkItem ProviderDefinition conflicts with the product contract';
    END IF;
END $$;

WITH organization_actor AS (
    SELECT DISTINCT ON (team.organization_id)
        team.organization_id,
        owner_member.user_principal_id AS actor_principal_id
    FROM crewscope.team team
    JOIN crewscope.workspace workspace
      ON workspace.organization_id = team.organization_id
     AND workspace.team_id = team.id
     AND workspace.id = team.default_workspace_id
    JOIN crewscope.team_member owner_member
      ON owner_member.organization_id = team.organization_id
     AND owner_member.team_id = team.id
     AND owner_member.id = team.owner_member_id
    WHERE team.status = 'ACTIVE'
      AND team.owner_member_id IS NOT NULL
      AND team.default_workspace_id IS NOT NULL
      AND workspace.workspace_type = 'TEAM'
      AND workspace.status = 'ACTIVE'
    ORDER BY team.organization_id, team.id
)
INSERT INTO crewscope.provider_implementation (
    id, organization_id, provider_definition_id, provider_type,
    definition_interface_version, implementation_key, implementation_version,
    capabilities, connection_requirement, connector_key, status, version,
    created_at, created_by_principal_id, updated_at, updated_by_principal_id
)
SELECT
    md5(
        'crewscope:built-in-provider:implementation:v1:native-work-item:'
        || definition.organization_id::TEXT
    )::UUID,
    definition.organization_id,
    definition.id,
    'WORK_ITEM',
    '1.0.0',
    'native-work-item',
    '1.0.0',
    definition.capabilities,
    'NONE',
    NULL,
    'ACTIVE',
    0,
    CURRENT_TIMESTAMP,
    actor.actor_principal_id,
    CURRENT_TIMESTAMP,
    actor.actor_principal_id
FROM crewscope.provider_definition definition
JOIN organization_actor actor USING (organization_id)
WHERE definition.provider_key = 'work-item'
ON CONFLICT (organization_id, provider_definition_id, implementation_key) DO NOTHING;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM crewscope.provider_implementation implementation
        JOIN crewscope.provider_definition definition
          ON definition.organization_id = implementation.organization_id
         AND definition.id = implementation.provider_definition_id
        WHERE definition.provider_key = 'work-item'
          AND implementation.implementation_key = 'native-work-item'
          AND (
              implementation.id <> md5(
                  'crewscope:built-in-provider:implementation:v1:native-work-item:'
                  || implementation.organization_id::TEXT
              )::UUID
              OR implementation.provider_type <> 'WORK_ITEM'
              OR implementation.definition_interface_version <> '1.0.0'
              OR implementation.implementation_version <> '1.0.0'
              OR implementation.capabilities <> definition.capabilities
              OR implementation.connection_requirement <> 'NONE'
              OR implementation.connector_key IS NOT NULL
          )
    ) THEN
        RAISE EXCEPTION 'NativeWorkItem ProviderImplementation conflicts with the product contract';
    END IF;
END $$;

WITH ready_team AS (
    SELECT
        team.organization_id,
        team.id AS team_id,
        team.default_workspace_id AS workspace_id,
        owner_member.user_principal_id AS actor_principal_id
    FROM crewscope.team team
    JOIN crewscope.workspace workspace
      ON workspace.organization_id = team.organization_id
     AND workspace.team_id = team.id
     AND workspace.id = team.default_workspace_id
    JOIN crewscope.team_member owner_member
      ON owner_member.organization_id = team.organization_id
     AND owner_member.team_id = team.id
     AND owner_member.id = team.owner_member_id
    WHERE team.status = 'ACTIVE'
      AND workspace.workspace_type = 'TEAM'
      AND workspace.status = 'ACTIVE'
), native_registry AS (
    SELECT
        definition.organization_id,
        definition.id AS definition_id,
        definition.version AS definition_version,
        implementation.id AS implementation_id,
        implementation.version AS implementation_version,
        definition.capabilities
    FROM crewscope.provider_definition definition
    JOIN crewscope.provider_implementation implementation
      ON implementation.organization_id = definition.organization_id
     AND implementation.provider_definition_id = definition.id
    WHERE definition.provider_key = 'work-item'
      AND implementation.implementation_key = 'native-work-item'
)
INSERT INTO crewscope.provider_binding (
    id, organization_id, team_id, workspace_id, target_type, work_project_id,
    owner_type, owner_id, owner_team_id, owner_user_principal_id,
    provider_definition_id, provider_definition_version, provider_type,
    provider_implementation_id, provider_implementation_version,
    connection_requirement, connection_id, connection_version,
    connection_grant_id, connection_grant_version, execution_identity,
    effective_capabilities, resource_unrestricted, effective_resources,
    default_usage, status, version,
    created_at, created_by_principal_id, updated_at, updated_by_principal_id
)
SELECT
    md5(
        'crewscope:built-in-provider:workspace-binding:v1:native-work-item:'
        || team.organization_id::TEXT || ':' || team.team_id::TEXT
    )::UUID,
    team.organization_id,
    team.team_id,
    team.workspace_id,
    'WORKSPACE',
    NULL,
    'TEAM',
    team.team_id,
    team.team_id,
    NULL,
    registry.definition_id,
    registry.definition_version,
    'WORK_ITEM',
    registry.implementation_id,
    registry.implementation_version,
    'NONE',
    NULL,
    NULL,
    NULL,
    NULL,
    NULL,
    registry.capabilities,
    FALSE,
    JSONB_BUILD_ARRAY('workspace:' || team.workspace_id::TEXT),
    TRUE,
    'ACTIVE',
    0,
    CURRENT_TIMESTAMP,
    team.actor_principal_id,
    CURRENT_TIMESTAMP,
    team.actor_principal_id
FROM ready_team team
JOIN native_registry registry USING (organization_id)
ON CONFLICT DO NOTHING;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM crewscope.team team
        JOIN crewscope.workspace workspace
          ON workspace.organization_id = team.organization_id
         AND workspace.team_id = team.id
         AND workspace.id = team.default_workspace_id
        LEFT JOIN crewscope.provider_binding binding
          ON binding.id = md5(
              'crewscope:built-in-provider:workspace-binding:v1:native-work-item:'
              || team.organization_id::TEXT || ':' || team.id::TEXT
          )::UUID
        WHERE team.status = 'ACTIVE'
          AND team.owner_member_id IS NOT NULL
          AND team.default_workspace_id IS NOT NULL
          AND workspace.workspace_type = 'TEAM'
          AND workspace.status = 'ACTIVE'
          AND (
              binding.id IS NULL
              OR binding.organization_id <> team.organization_id
              OR binding.team_id <> team.id
              OR binding.workspace_id <> team.default_workspace_id
              OR binding.target_type <> 'WORKSPACE'
              OR binding.work_project_id IS NOT NULL
              OR binding.owner_type <> 'TEAM'
              OR binding.owner_id <> team.id
              OR binding.owner_team_id <> team.id
              OR binding.provider_type <> 'WORK_ITEM'
              OR binding.connection_requirement <> 'NONE'
              OR binding.connection_id IS NOT NULL
              OR binding.connection_grant_id IS NOT NULL
              OR binding.execution_identity IS NOT NULL
              OR NOT binding.default_usage
          )
    ) THEN
        RAISE EXCEPTION 'NativeWorkItem ProviderBinding backfill is incomplete or incompatible';
    END IF;
END $$;
