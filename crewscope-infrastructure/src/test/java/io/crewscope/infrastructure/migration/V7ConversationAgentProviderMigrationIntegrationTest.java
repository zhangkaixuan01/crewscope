package io.crewscope.infrastructure.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.application.execution.RealtimeStreamEventIds;
import io.crewscope.application.provider.BuiltInProviderRegistration;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.event.StreamType;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies the V7 Conversation, Agent runtime session and Provider binding database contract. */
class V7ConversationAgentProviderMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_6 = MigrationVersion.fromVersion("6");
    private static final MigrationVersion VERSION_7 = MigrationVersion.fromVersion("7");
    private static final MigrationVersion VERSION_8 = MigrationVersion.fromVersion("8");
    private static final MigrationVersion VERSION_9 = MigrationVersion.fromVersion("9");
    private static final String ALTERNATE_SCHEMA = "v7_probe";

    @BeforeEach
    void resetSchemas() throws SQLException {
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS v7_probe CASCADE");
            statement.execute("CREATE SCHEMA v7_probe");
        }
    }

    @Test
    void createsAllM2TablesAuditColumnsConstraintsAndIndexesFromEmptyDatabase()
            throws SQLException {
        Flyway flyway = flyway(POSTGRES.getJdbcUrl(), VERSION_7);
        flyway.migrate();

        assertEquals("7", flyway.info().current().getVersion().getVersion());
        Set<String> tables = queryStrings(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'crewscope'
                """);
        assertTrue(tables.containsAll(Set.of(
                "conversation",
                "conversation_participant",
                "message",
                "task_intent",
                "conversation_work_item_link",
                "agent_runtime_session",
                "provider_definition",
                "provider_implementation",
                "connection",
                "connection_grant",
                "provider_binding")));

        for (String table : Set.of(
                "conversation",
                "conversation_participant",
                "task_intent",
                "agent_runtime_session",
                "provider_definition",
                "provider_implementation",
                "connection",
                "connection_grant",
                "provider_binding")) {
            assertTrue(
                    columns(table).containsAll(Set.of(
                            "version",
                            "created_at",
                            "created_by_principal_id",
                            "updated_at",
                            "updated_by_principal_id")),
                    table);
        }
        assertTrue(columns("message").containsAll(Set.of(
                "client_message_key",
                "moderation_status",
                "moderated_at",
                "moderated_by_principal_id",
                "moderation_reason_code")));
        assertTrue(columns("task_intent").contains("confirmed_work_item_id"));
        assertTrue(columns("provider_binding").contains("connection_requirement"));

        Set<String> constraints = queryStrings(
                """
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE constraint_schema = 'crewscope'
                  AND table_name IN (
                    'conversation', 'conversation_participant', 'message',
                    'task_intent', 'conversation_work_item_link',
                    'agent_runtime_session', 'provider_implementation',
                    'connection', 'connection_grant', 'provider_binding'
                  )
                """);
        assertTrue(constraints.containsAll(Set.of(
                "fk_conversation_owner_member",
                "fk_conversation_participant_member",
                "fk_message_participant_author",
                "fk_task_intent_confirmed_work_item",
                "fk_agent_runtime_session_profile",
                "fk_provider_implementation_definition",
                "fk_connection_grant_connection_owner",
                "fk_provider_binding_implementation",
                "fk_provider_binding_grant",
                "ck_provider_binding_connection_shape")));

        Set<String> indexes = queryStrings(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'crewscope'");
        assertTrue(indexes.containsAll(Set.of(
                "ux_conversation_participant_active",
                "ux_message_client_key",
                "uk_message_conversation_sequence",
                "ux_task_intent_confirmed_work_item",
                "ux_agent_runtime_session_active_binding",
                "ux_provider_binding_active_default",
                "ix_provider_binding_resolver")));
    }

    @Test
    void upgradesPopulatedV6DatabaseWithoutChangingExistingFacts() throws SQLException {
        Flyway versionSix = flyway(POSTGRES.getJdbcUrl(), VERSION_6);
        versionSix.migrate();
        Fixture fixture = seedFixture("UPG");
        insertCredential(fixture, UUID.randomUUID(), "upgrade-token");

        Flyway versionSeven = flyway(POSTGRES.getJdbcUrl(), VERSION_7);
        assertEquals(1, versionSeven.migrate().migrationsExecuted);

        assertEquals("7", versionSeven.info().current().getVersion().getVersion());
        assertEquals(1, queryInt(
                "SELECT COUNT(*) FROM crewscope.agent_profile WHERE id = ?",
                fixture.agentProfileId()));
        assertEquals(1, queryInt(
                "SELECT COUNT(*) FROM crewscope.work_item WHERE id = ?",
                fixture.workItemId()));
        assertEquals(1, queryInt(
                "SELECT COUNT(*) FROM crewscope.credential_secret WHERE organization_id = ?",
                fixture.organizationId()));
        assertEquals(0, queryInt("SELECT COUNT(*) FROM crewscope.conversation"));
    }

    @Test
    void backfillsOneStableConnectionlessNativeBindingForEveryReadyTeam() throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_8).migrate();
        Fixture ready = seedFixture("NAT");
        Fixture incomplete = seedFixture("INC");
        execute(
                "UPDATE crewscope.team SET owner_member_id = ?, default_workspace_id = ? WHERE id = ?",
                ready.memberId(),
                ready.workspaceId(),
                ready.teamId());

        Flyway versionNine = flyway(POSTGRES.getJdbcUrl(), VERSION_9);
        assertEquals(1, versionNine.migrate().migrationsExecuted);

        BuiltInProviderRegistration registration = nativeRegistration();
        UUID expectedDefinitionId = registration
                .definitionId(new OrganizationId(ready.organizationId()))
                .value();
        UUID expectedImplementationId = registration
                .implementationId(new OrganizationId(ready.organizationId()))
                .value();
        UUID expectedBindingId = registration
                .workspaceBindingId(
                        new OrganizationId(ready.organizationId()),
                        new TeamId(ready.teamId()))
                .value();
        assertEquals(expectedDefinitionId, queryUuid(
                "SELECT id FROM crewscope.provider_definition WHERE organization_id = ? AND provider_key = 'work-item'",
                ready.organizationId()));
        assertEquals(expectedImplementationId, queryUuid(
                "SELECT id FROM crewscope.provider_implementation WHERE organization_id = ? AND implementation_key = 'native-work-item'",
                ready.organizationId()));
        assertEquals(expectedBindingId, queryUuid(
                "SELECT id FROM crewscope.provider_binding WHERE organization_id = ? AND team_id = ? AND provider_type = 'WORK_ITEM'",
                ready.organizationId(),
                ready.teamId()));
        assertEquals(1, queryInt(
                """
                SELECT COUNT(*)
                FROM crewscope.provider_binding
                WHERE id = ?
                  AND workspace_id = ?
                  AND target_type = 'WORKSPACE'
                  AND owner_type = 'TEAM'
                  AND owner_id = ?
                  AND connection_requirement = 'NONE'
                  AND connection_id IS NULL
                  AND connection_grant_id IS NULL
                  AND execution_identity IS NULL
                  AND effective_resources = JSONB_BUILD_ARRAY('workspace:' || ?::TEXT)
                  AND default_usage
                  AND status = 'ACTIVE'
                """,
                expectedBindingId,
                ready.workspaceId(),
                ready.teamId(),
                ready.workspaceId()));
        assertEquals(0, queryInt(
                "SELECT COUNT(*) FROM crewscope.provider_binding WHERE organization_id = ?",
                incomplete.organizationId()));
    }

    @Test
    void upgradesV7ConversationFactsIntoStableOrderedEventStreams() throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_7).migrate();
        Fixture fixture = seedFixture("EVT");
        UUID conversationId = insertConversation(fixture);
        UUID directEventId = UUID.randomUUID();
        UUID participantEventId = UUID.randomUUID();
        insertConversationDomainEvent(
                fixture,
                directEventId,
                "CONVERSATION_CREATED",
                "CONVERSATION",
                conversationId,
                0,
                "2026-08-10T08:00:00Z",
                "{}");
        insertConversationDomainEvent(
                fixture,
                participantEventId,
                "CONVERSATION_PARTICIPANT_JOINED",
                "CONVERSATION_PARTICIPANT",
                UUID.randomUUID(),
                0,
                "2026-08-10T08:01:00Z",
                "{\"conversationId\":\"" + conversationId + "\"}");

        Flyway versionEight = flyway(POSTGRES.getJdbcUrl(), VERSION_8);
        assertEquals(1, versionEight.migrate().migrationsExecuted);

        assertEquals(2, queryInt("SELECT COUNT(*) FROM crewscope.conversation_event"));
        assertEquals(
                Set.of("1", "2"),
                queryStrings(
                        "SELECT position FROM crewscope.conversation_event ORDER BY position"));
        assertEquals(
                RealtimeStreamEventIds.forDomain(StreamType.CONVERSATION, directEventId),
                queryUuid(
                        "SELECT event_id FROM crewscope.conversation_event WHERE domain_event_id = ?",
                        directEventId));
    }

    @Test
    void migratesV7IntoCrewscopeWhenConnectionUsesNonDefaultSearchPath()
            throws SQLException {
        String jdbcUrl = POSTGRES.getJdbcUrl()
                + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + ALTERNATE_SCHEMA;
        assertEquals(ALTERNATE_SCHEMA, queryString(jdbcUrl, "SELECT current_schema()"));

        Flyway flyway = flyway(jdbcUrl, VERSION_7);
        flyway.migrate();

        assertEquals(1, tableCount("crewscope", "provider_binding"));
        assertEquals(0, tableCount(ALTERNATE_SCHEMA, "provider_binding"));
        assertEquals(1, tableCount("crewscope", "flyway_schema_history"));
        assertEquals(0, tableCount(ALTERNATE_SCHEMA, "flyway_schema_history"));
    }

    @Test
    void enforcesConversationScopeActiveParticipantAndMessageIdempotency()
            throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_7).migrate();
        Fixture fixture = seedFixture("CON");
        UUID conversationId = insertConversation(fixture);
        UUID ownerParticipantId = insertParticipant(
                fixture,
                conversationId,
                fixture.userPrincipalId(),
                fixture.memberId(),
                "OWNER");
        UUID agentParticipantId = insertParticipant(
                fixture,
                conversationId,
                fixture.agentPrincipalId(),
                null,
                "AGENT");

        assertSqlState(
                "23505",
                participantInsertSql(),
                UUID.randomUUID(),
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                conversationId,
                fixture.userPrincipalId(),
                fixture.memberId(),
                "OWNER",
                fixture.userPrincipalId(),
                fixture.userPrincipalId(),
                fixture.userPrincipalId());
        insertMessage(
                fixture,
                conversationId,
                ownerParticipantId,
                fixture.userPrincipalId(),
                1,
                "client-1",
                "USER_MESSAGE");
        assertSqlState(
                "23505",
                messageInsertSql(),
                UUID.randomUUID(),
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                conversationId,
                1L,
                "AGENT_MESSAGE",
                agentParticipantId,
                fixture.agentPrincipalId(),
                "duplicate sequence",
                "client-2",
                fixture.agentPrincipalId(),
                fixture.agentPrincipalId());
        assertSqlState(
                "23505",
                messageInsertSql(),
                UUID.randomUUID(),
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                conversationId,
                2L,
                "USER_MESSAGE",
                ownerParticipantId,
                fixture.userPrincipalId(),
                "duplicate key",
                "client-1",
                fixture.userPrincipalId(),
                fixture.userPrincipalId());
        assertSqlState(
                "23503",
                messageInsertSql(),
                UUID.randomUUID(),
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                conversationId,
                3L,
                "AGENT_MESSAGE",
                ownerParticipantId,
                fixture.agentPrincipalId(),
                "forged participant author",
                "client-3",
                fixture.agentPrincipalId(),
                fixture.agentPrincipalId());
    }

    @Test
    void enforcesAgentRuntimeSessionProfileSnapshotAndSingleActiveBinding()
            throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_7).migrate();
        Fixture fixture = seedFixture("SES");
        UUID conversationId = insertConversation(fixture);
        insertRuntimeSession(fixture, conversationId, UUID.randomUUID(), 0);
        execute(
                "UPDATE crewscope.agent_profile SET version = 1 WHERE id = ?",
                fixture.agentProfileId());
        assertEquals(1, queryInt(
                "SELECT version FROM crewscope.agent_profile WHERE id = ?",
                fixture.agentProfileId()));

        UUID duplicateSessionId = UUID.randomUUID();
        assertSqlState(
                "23505",
                runtimeSessionInsertSql(),
                duplicateSessionId,
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                conversationId,
                fixture.memberId(),
                fixture.userPrincipalId(),
                fixture.agentPrincipalId(),
                fixture.agentProfileId(),
                0L,
                "crewscope:v1:user:" + UUID.randomUUID(),
                "crewscope:v1:session:" + UUID.randomUUID(),
                "crewscope:agent-state:v1:" + duplicateSessionId,
                fixture.userPrincipalId(),
                fixture.userPrincipalId());
        execute(
                """
                UPDATE crewscope.agent_runtime_session
                SET status = 'DISABLED', version = version + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by_principal_id = ?
                WHERE conversation_id = ? AND status = 'ACTIVE'
                """,
                fixture.userPrincipalId(),
                conversationId);
        UUID mismatchedProfileSessionId = UUID.randomUUID();
        assertSqlState(
                "23503",
                runtimeSessionInsertSql(),
                mismatchedProfileSessionId,
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                conversationId,
                fixture.memberId(),
                fixture.userPrincipalId(),
                fixture.agentPrincipalId(),
                UUID.randomUUID(),
                0L,
                "crewscope:v1:user:" + UUID.randomUUID(),
                "crewscope:v1:session:" + UUID.randomUUID(),
                "crewscope:agent-state:v1:" + mismatchedProfileSessionId,
                fixture.userPrincipalId(),
                fixture.userPrincipalId());
    }

    @Test
    void enforcesTaskIntentDecisionScopeAndSingleConfirmedWorkItem()
            throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_7).migrate();
        Fixture fixture = seedFixture("INT");
        UUID conversationId = insertConversation(fixture);

        assertTaskIntentSqlState(
                "23514",
                UUID.randomUUID(),
                fixture,
                conversationId,
                "CONFIRMED",
                fixture.userPrincipalId(),
                null);
        insertTaskIntent(
                UUID.randomUUID(),
                fixture,
                conversationId,
                "CONFIRMED",
                fixture.userPrincipalId(),
                fixture.workItemId());
        assertTaskIntentSqlState(
                "23505",
                UUID.randomUUID(),
                fixture,
                conversationId,
                "CONFIRMED",
                fixture.userPrincipalId(),
                fixture.workItemId());
    }

    @Test
    void enforcesProviderOwnerGrantBindingScopeAndDefaultUniqueness()
            throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_7).migrate();
        Fixture fixture = seedFixture("PRV");
        UUID credentialId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        UUID implementationId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        UUID grantId = UUID.randomUUID();
        insertCredential(fixture, credentialId, "github-token");
        insertProviderDefinition(fixture, definitionId);
        insertProviderImplementation(fixture, definitionId, implementationId);
        insertOrganizationConnection(fixture, connectionId, credentialId);
        insertTeamGrant(fixture, connectionId, grantId);
        insertProviderBinding(
                fixture,
                UUID.randomUUID(),
                definitionId,
                implementationId,
                connectionId,
                grantId);

        execute(
                "UPDATE crewscope.provider_definition SET version = 1 WHERE id = ?",
                definitionId);
        execute(
                "UPDATE crewscope.provider_implementation SET version = 1 WHERE id = ?",
                implementationId);
        execute(
                "UPDATE crewscope.connection SET version = 1 WHERE id = ?",
                connectionId);
        execute(
                "UPDATE crewscope.connection_grant SET version = 1 WHERE id = ?",
                grantId);
        assertEquals(4, queryInt(
                """
                SELECT
                    (SELECT version FROM crewscope.provider_definition WHERE id = ?)
                  + (SELECT version FROM crewscope.provider_implementation WHERE id = ?)
                  + (SELECT version FROM crewscope.connection WHERE id = ?)
                  + (SELECT version FROM crewscope.connection_grant WHERE id = ?)
                """,
                definitionId,
                implementationId,
                connectionId,
                grantId));

        assertProviderBindingSqlState(
                "23505",
                UUID.randomUUID(),
                fixture,
                definitionId,
                implementationId,
                connectionId,
                grantId);
        assertSqlState(
                "23514",
                connectionGrantInsertSql(),
                UUID.randomUUID(),
                fixture.organizationId(),
                connectionId,
                "ORGANIZATION",
                fixture.organizationId(),
                "TEAM",
                fixture.teamId(),
                null,
                null,
                fixture.userPrincipalId(),
                fixture.userPrincipalId());
        assertNativeShapeBindingSqlState(
                "23503",
                UUID.randomUUID(),
                fixture,
                definitionId,
                implementationId);
    }

    private static Fixture seedFixture(String projectKey) throws SQLException {
        UUID organizationId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID userPrincipalId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID agentPrincipalId = UUID.randomUUID();
        UUID agentProfileId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID workItemId = UUID.randomUUID();
        execute(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Org', 'ACTIVE')",
                organizationId);
        execute(
                """
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES (?, ?, 'Team', 'ACTIVE')
                """,
                teamId,
                organizationId);
        execute(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', 'Workspace', 'ACTIVE')
                """,
                workspaceId,
                organizationId,
                teamId);
        execute(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES (?, ?, 'USER', 'Owner', 'ACTIVE')
                """,
                userPrincipalId,
                organizationId);
        execute(
                """
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id,
                    status, join_method, joined_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', 'BOOTSTRAP', CURRENT_TIMESTAMP)
                """,
                memberId,
                organizationId,
                teamId,
                userPrincipalId);
        execute(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, team_id, principal_type,
                    owner_principal_id, display_name, visibility, status
                ) VALUES (?, ?, ?, 'PERSONAL_AGENT', ?, 'Personal Agent', 'PRIVATE', 'ACTIVE')
                """,
                agentPrincipalId,
                organizationId,
                teamId,
                userPrincipalId);
        execute(
                """
                INSERT INTO crewscope.agent_profile (
                    id, organization_id, team_id, workspace_id,
                    agent_principal_id, owner_member_id,
                    profile_type, default_profile, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'PERSONAL', TRUE, 'ACTIVE', ?, ?)
                """,
                agentProfileId,
                organizationId,
                teamId,
                workspaceId,
                agentPrincipalId,
                memberId,
                userPrincipalId,
                userPrincipalId);
        execute(
                """
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id,
                    project_key, name, created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, 'Project', ?, ?)
                """,
                projectId,
                organizationId,
                teamId,
                workspaceId,
                projectKey,
                userPrincipalId,
                userPrincipalId);
        execute(
                """
                INSERT INTO crewscope.work_item (
                    id, organization_id, team_id, workspace_id, project_id,
                    item_key, item_type, title, status, priority,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'TASK', 'Item', 'BACKLOG', 'MEDIUM', ?, ?)
                """,
                workItemId,
                organizationId,
                teamId,
                workspaceId,
                projectId,
                projectKey + "-1",
                userPrincipalId,
                userPrincipalId);
        return new Fixture(
                organizationId,
                teamId,
                workspaceId,
                userPrincipalId,
                memberId,
                agentPrincipalId,
                agentProfileId,
                projectId,
                workItemId);
    }

    private static BuiltInProviderRegistration nativeRegistration() {
        return new BuiltInProviderRegistration(
                "work-item",
                ProviderType.WORK_ITEM,
                "1.0.0",
                "CrewScope WorkItem",
                "native-work-item",
                "1.0.0",
                ProviderCapabilities.of(
                        "workitem.read",
                        "workitem.create",
                        "workitem.update",
                        "workitem.comment",
                        "workitem.resource-link"));
    }

    private static UUID insertConversation(Fixture fixture) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(
                """
                INSERT INTO crewscope.conversation (
                    id, organization_id, team_id, workspace_id,
                    owner_member_id, owner_principal_id, personal_agent_principal_id,
                    title, visibility, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'Conversation', 'PRIVATE', 'ACTIVE', ?, ?)
                """,
                id,
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                fixture.memberId(),
                fixture.userPrincipalId(),
                fixture.agentPrincipalId(),
                fixture.userPrincipalId(),
                fixture.userPrincipalId());
        return id;
    }

    private static UUID insertParticipant(
            Fixture fixture,
            UUID conversationId,
            UUID principalId,
            UUID memberId,
            String role)
            throws SQLException {
        UUID id = UUID.randomUUID();
        execute(
                participantInsertSql(),
                id,
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                conversationId,
                principalId,
                memberId,
                role,
                fixture.userPrincipalId(),
                fixture.userPrincipalId(),
                fixture.userPrincipalId());
        return id;
    }

    private static void insertConversationDomainEvent(
            Fixture fixture,
            UUID eventId,
            String eventType,
            String subjectType,
            UUID subjectId,
            long aggregateVersion,
            String occurredAt,
            String payload)
            throws SQLException {
        execute(
                """
                INSERT INTO crewscope.domain_event (
                    event_id, event_type, schema_version,
                    organization_id, team_id, workspace_id,
                    subject_type, subject_id, aggregate_version,
                    actor_type, actor_id, correlation_id,
                    occurred_at, payload
                ) VALUES (?, ?, '1', ?, ?, ?, ?, ?, ?, 'USER', ?, ?,
                          CAST(? AS TIMESTAMPTZ), CAST(? AS JSONB))
                """,
                eventId,
                eventType,
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                subjectType,
                subjectId,
                aggregateVersion,
                fixture.userPrincipalId(),
                UUID.randomUUID(),
                occurredAt,
                payload);
    }

    private static String participantInsertSql() {
        return """
                INSERT INTO crewscope.conversation_participant (
                    id, organization_id, team_id, workspace_id, conversation_id,
                    principal_id, team_member_id, role, status,
                    joined_by_principal_id, joined_at,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, CURRENT_TIMESTAMP, ?, ?)
                """;
    }

    private static void insertMessage(
            Fixture fixture,
            UUID conversationId,
            UUID participantId,
            UUID authorId,
            long sequence,
            String clientKey,
            String messageType)
            throws SQLException {
        execute(
                messageInsertSql(),
                UUID.randomUUID(),
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                conversationId,
                sequence,
                messageType,
                participantId,
                authorId,
                "message",
                clientKey,
                authorId,
                authorId);
    }

    private static String messageInsertSql() {
        return """
                INSERT INTO crewscope.message (
                    id, organization_id, team_id, workspace_id, conversation_id,
                    sequence, message_type, participant_id, author_principal_id,
                    content_markdown, client_message_key,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
    }

    private static void insertRuntimeSession(
            Fixture fixture, UUID conversationId, UUID sessionId, long profileVersion)
            throws SQLException {
        execute(
                runtimeSessionInsertSql(),
                sessionId,
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                conversationId,
                fixture.memberId(),
                fixture.userPrincipalId(),
                fixture.agentPrincipalId(),
                fixture.agentProfileId(),
                profileVersion,
                "crewscope:v1:user:" + fixture.memberId(),
                "crewscope:v1:session:" + conversationId + ":" + sessionId,
                "crewscope:agent-state:v1:" + sessionId,
                fixture.userPrincipalId(),
                fixture.userPrincipalId());
    }

    private static String runtimeSessionInsertSql() {
        return """
                INSERT INTO crewscope.agent_runtime_session (
                    id, organization_id, team_id, workspace_id, conversation_id,
                    owner_member_id, owner_principal_id, personal_agent_principal_id,
                    agent_profile_id, agent_profile_version,
                    agent_scope_user_id, agent_scope_session_id, state_reference,
                    status, created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """;
    }

    private static void insertTaskIntent(
            UUID id,
            Fixture fixture,
            UUID conversationId,
            String status,
            UUID decidedBy,
            UUID confirmedWorkItemId)
            throws SQLException {
        execute(
                taskIntentInsertSql(),
                id,
                fixture,
                conversationId,
                status,
                decidedBy,
                confirmedWorkItemId);
    }

    private static String taskIntentInsertSql() {
        return """
                INSERT INTO crewscope.task_intent (
                    id, organization_id, team_id, workspace_id, conversation_id,
                    proposed_by_principal_id, proposal_revision, work_project_id,
                    objective, acceptance_criteria,
                    owner_principal_id, owner_principal_type, owner_member_id,
                    status, decided_by_principal_id, decided_at, confirmed_work_item_id,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, 1, ?, 'Ship M2', '["verified"]'::JSONB,
                    ?, 'USER', ?, ?, ?,
                    CASE WHEN ? IS NULL THEN NULL ELSE CURRENT_TIMESTAMP END,
                    ?, ?, COALESCE(?, ?)
                )
                """;
    }

    /** Expands a fixture-shaped TaskIntent call into scalar JDBC values. */
    private static int execute(
            String sql,
            UUID id,
            Fixture fixture,
            UUID conversationId,
            String status,
            UUID decidedBy,
            UUID confirmedWorkItemId)
            throws SQLException {
        return execute(
                sql,
                id,
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                conversationId,
                fixture.agentPrincipalId(),
                fixture.projectId(),
                fixture.userPrincipalId(),
                fixture.memberId(),
                status,
                decidedBy,
                decidedBy,
                confirmedWorkItemId,
                fixture.agentPrincipalId(),
                decidedBy,
                fixture.agentPrincipalId());
    }

    private static void assertTaskIntentSqlState(
            String state,
            UUID id,
            Fixture fixture,
            UUID conversationId,
            String status,
            UUID decidedBy,
            UUID confirmedWorkItemId) {
        SQLException exception = assertThrows(
                SQLException.class,
                () -> execute(
                        taskIntentInsertSql(),
                        id,
                        fixture,
                        conversationId,
                        status,
                        decidedBy,
                        confirmedWorkItemId));
        assertEquals(state, exception.getSQLState());
    }

    private static void insertCredential(Fixture fixture, UUID id, String key)
            throws SQLException {
        execute(
                """
                INSERT INTO crewscope.credential_secret (
                    id, organization_id, subject_type, subject_id,
                    credential_key, provider_key, credential_type,
                    ciphertext, nonce, authentication_tag,
                    key_id, algorithm, aad_version, status
                ) VALUES (?, ?, 'ORGANIZATION', ?, ?, 'github', 'OAUTH_TOKEN',
                          ?, ?, ?, 'test-key', 'AES-256-GCM', 'v1', 'ACTIVE')
                """,
                id,
                fixture.organizationId(),
                fixture.organizationId(),
                key,
                new byte[] {1},
                new byte[12],
                new byte[16]);
    }

    private static void insertProviderDefinition(Fixture fixture, UUID definitionId)
            throws SQLException {
        execute(
                """
                INSERT INTO crewscope.provider_definition (
                    id, organization_id, provider_key, provider_type,
                    interface_version, display_name, capabilities, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, 'github-source', 'SOURCE_CODE', 'v1', 'GitHub',
                          '["repository.read"]'::JSONB, 'ACTIVE', ?, ?)
                """,
                definitionId,
                fixture.organizationId(),
                fixture.userPrincipalId(),
                fixture.userPrincipalId());
    }

    private static void insertProviderImplementation(
            Fixture fixture, UUID definitionId, UUID implementationId) throws SQLException {
        execute(
                """
                INSERT INTO crewscope.provider_implementation (
                    id, organization_id, provider_definition_id, provider_type,
                    definition_interface_version, implementation_key,
                    implementation_version, capabilities,
                    connection_requirement, connector_key, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, 'SOURCE_CODE', 'v1', 'github-app', '2.0.0',
                          '["repository.read"]'::JSONB,
                          'REQUIRED', 'github', 'ACTIVE', ?, ?)
                """,
                implementationId,
                fixture.organizationId(),
                definitionId,
                fixture.userPrincipalId(),
                fixture.userPrincipalId());
    }

    private static void insertOrganizationConnection(
            Fixture fixture, UUID connectionId, UUID credentialId) throws SQLException {
        execute(
                """
                INSERT INTO crewscope.connection (
                    id, organization_id, owner_type, owner_id,
                    connector_key, external_account_reference, credential_id, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, 'ORGANIZATION', ?, 'github', 'installation:1', ?, 'ACTIVE', ?, ?)
                """,
                connectionId,
                fixture.organizationId(),
                fixture.organizationId(),
                credentialId,
                fixture.userPrincipalId(),
                fixture.userPrincipalId());
    }

    private static void insertTeamGrant(
            Fixture fixture, UUID connectionId, UUID grantId) throws SQLException {
        execute(
                connectionGrantInsertSql(),
                grantId,
                fixture.organizationId(),
                connectionId,
                "ORGANIZATION",
                fixture.organizationId(),
                "TEAM",
                fixture.teamId(),
                fixture.teamId(),
                null,
                fixture.userPrincipalId(),
                fixture.userPrincipalId());
    }

    private static String connectionGrantInsertSql() {
        return """
                INSERT INTO crewscope.connection_grant (
                    id, organization_id, connection_id,
                    connection_owner_type, connection_owner_id,
                    grantee_type, grantee_id, grantee_team_id, grantee_user_principal_id,
                    granted_capabilities, resource_unrestricted, granted_resources,
                    valid_from, status, created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,
                          '["repository.read"]'::JSONB, TRUE, '[]'::JSONB,
                          CURRENT_TIMESTAMP, 'ACTIVE', ?, ?)
                """;
    }

    private static void insertProviderBinding(
            Fixture fixture,
            UUID bindingId,
            UUID definitionId,
            UUID implementationId,
            UUID connectionId,
            UUID grantId)
            throws SQLException {
        execute(
                providerBindingInsertSql(),
                bindingId,
                fixture,
                definitionId,
                implementationId,
                connectionId,
                grantId);
    }

    private static String providerBindingInsertSql() {
        return """
                INSERT INTO crewscope.provider_binding (
                    id, organization_id, team_id, workspace_id,
                    target_type, owner_type, owner_id, owner_team_id,
                    provider_definition_id, provider_definition_version, provider_type,
                    provider_implementation_id, provider_implementation_version,
                    connection_requirement, connection_id, connection_version,
                    connection_grant_id, connection_grant_version, execution_identity,
                    effective_capabilities, resource_unrestricted, effective_resources,
                    default_usage, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, 'WORKSPACE', 'TEAM', ?, ?,
                          ?, 0, 'SOURCE_CODE', ?, 0,
                          'REQUIRED', ?, 0, ?, 0, 'ORGANIZATION_SERVICE_ACCOUNT',
                          '["repository.read"]'::JSONB, TRUE, '[]'::JSONB,
                          TRUE, 'ACTIVE', ?, ?)
                """;
    }

    private static int execute(
            String sql,
            UUID bindingId,
            Fixture fixture,
            UUID definitionId,
            UUID implementationId,
            UUID connectionId,
            UUID grantId)
            throws SQLException {
        return execute(
                sql,
                bindingId,
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                fixture.teamId(),
                fixture.teamId(),
                definitionId,
                implementationId,
                connectionId,
                grantId,
                fixture.userPrincipalId(),
                fixture.userPrincipalId());
    }

    private static void assertProviderBindingSqlState(
            String state,
            UUID bindingId,
            Fixture fixture,
            UUID definitionId,
            UUID implementationId,
            UUID connectionId,
            UUID grantId) {
        SQLException exception = assertThrows(
                SQLException.class,
                () -> execute(
                        providerBindingInsertSql(),
                        bindingId,
                        fixture,
                        definitionId,
                        implementationId,
                        connectionId,
                        grantId));
        assertEquals(state, exception.getSQLState());
    }

    private static String nativeShapeBindingInsertSql() {
        return """
                INSERT INTO crewscope.provider_binding (
                    id, organization_id, team_id, workspace_id,
                    target_type, owner_type, owner_id, owner_team_id,
                    provider_definition_id, provider_definition_version, provider_type,
                    provider_implementation_id, provider_implementation_version,
                    connection_requirement,
                    effective_capabilities, resource_unrestricted, effective_resources,
                    default_usage, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, 'WORKSPACE', 'TEAM', ?, ?,
                          ?, 0, 'SOURCE_CODE', ?, 0, 'NONE',
                          '["repository.read"]'::JSONB, TRUE, '[]'::JSONB,
                          FALSE, 'ACTIVE', ?, ?)
                """;
    }

    private static int execute(
            String sql,
            UUID bindingId,
            Fixture fixture,
            UUID definitionId,
            UUID implementationId)
            throws SQLException {
        return execute(
                sql,
                bindingId,
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                fixture.teamId(),
                fixture.teamId(),
                definitionId,
                implementationId,
                fixture.userPrincipalId(),
                fixture.userPrincipalId());
    }

    private static void assertNativeShapeBindingSqlState(
            String state,
            UUID bindingId,
            Fixture fixture,
            UUID definitionId,
            UUID implementationId) {
        SQLException exception = assertThrows(
                SQLException.class,
                () -> execute(
                        nativeShapeBindingInsertSql(),
                        bindingId,
                        fixture,
                        definitionId,
                        implementationId));
        assertEquals(state, exception.getSQLState());
    }

    private static Flyway flyway(String jdbcUrl, MigrationVersion target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas("crewscope")
                .defaultSchema("crewscope")
                .createSchemas(true)
                .validateMigrationNaming(true);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static Connection openConnection(String jdbcUrl) throws SQLException {
        return DriverManager.getConnection(
                jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static int execute(String sql, Object... values) throws SQLException {
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            return statement.executeUpdate();
        }
    }

    private static void assertSqlState(String state, String sql, Object... values) {
        SQLException exception = assertThrows(SQLException.class, () -> execute(sql, values));
        assertEquals(state, exception.getSQLState());
    }

    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
    }

    private static Set<String> columns(String table) throws SQLException {
        return queryStrings(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'crewscope' AND table_name = '%s'
                """.formatted(table));
    }

    private static int tableCount(String schema, String table) throws SQLException {
        return queryInt(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = ? AND table_name = ?
                """,
                schema,
                table);
    }

    private static int queryInt(String sql, Object... values) throws SQLException {
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getInt(1);
            }
        }
    }

    private static String queryString(String jdbcUrl, String sql) throws SQLException {
        try (Connection connection = openConnection(jdbcUrl);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static UUID queryUuid(String sql, Object... values) throws SQLException {
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getObject(1, UUID.class);
            }
        }
    }

    private static Set<String> queryStrings(String sql) throws SQLException {
        Set<String> values = new HashSet<>();
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
        }
        return values;
    }

    private record Fixture(
            UUID organizationId,
            UUID teamId,
            UUID workspaceId,
            UUID userPrincipalId,
            UUID memberId,
            UUID agentPrincipalId,
            UUID agentProfileId,
            UUID projectId,
            UUID workItemId) {}
}
