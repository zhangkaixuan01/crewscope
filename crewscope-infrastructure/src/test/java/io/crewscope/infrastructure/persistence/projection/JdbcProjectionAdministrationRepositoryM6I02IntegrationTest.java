package io.crewscope.infrastructure.persistence.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.audit.CrewScopeAuditEventTypes;
import io.crewscope.application.projection.*;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.projection.*;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.event.projection.AuditEventProjector;
import io.crewscope.infrastructure.persistence.event.JdbcDomainEventStore;
import io.crewscope.infrastructure.persistence.event.JdbcOutboxRepository;
import io.crewscope.infrastructure.persistence.operations.AtomicOperationsEventWriter;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.transaction.SpringTransactionExecutor;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL lifecycle, receipt and atomic Audit/Outbox coverage for M6-I02. */
@SpringBootTest(
        classes = JdbcProjectionAdministrationRepositoryM6I02IntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "crewscope.projection.supervisor.enabled=false"
        })
class JdbcProjectionAdministrationRepositoryM6I02IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp NOW = UtcTimestamp.from(
            Instant.parse("2026-08-26T09:00:00Z"));
    private static final ProjectionName PROJECTION_NAME = new ProjectionName("test-admin-view");
    private static final ProjectionDefinitionVersion DEFINITION_VERSION =
            ProjectionDefinitionVersion.V1;
    private static final ProjectionSnapshot HEALTHY = new ProjectionSnapshot(
            2, new ProjectionCanonicalHash("a".repeat(64)), 0, List.of());

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private OrganizationId organizationId;
    private Principal actor;
    private TeamAccessContext access;
    private JdbcProjectionAdministrationRepositoryAdapter repository;
    private ProjectionAdministrationService service;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        jdbc.update(
                "DELETE FROM crewscope.projection_definition WHERE projection_name = ?",
                PROJECTION_NAME.value());
        organizationId = OrganizationId.generate();
        PrincipalId principalId = PrincipalId.generate();
        actor = Principal.create(
                principalId,
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Administrator",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        access = new TeamAccessContext(actor, true);
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Org', 'ACTIVE')",
                organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', 'Administrator', 'ORGANIZATION', 'ACTIVE')
                """,
                principalId.value(), organizationId.value());
        seedRegistry();

        AuditEventProjector audit = new AuditEventProjector(
                jdbc, objectMapper, CrewScopeAuditEventTypes.reviewedRegistry());
        AtomicOperationsEventWriter eventWriter = new AtomicOperationsEventWriter(
                jdbc,
                new JdbcDomainEventStore(jdbc, objectMapper),
                new JdbcOutboxRepository(jdbc),
                audit,
                objectMapper);
        repository = new JdbcProjectionAdministrationRepositoryAdapter(jdbc, eventWriter);
        ProjectionAdministration administration = mock(ProjectionAdministration.class);
        ProjectionSnapshotVerifier verifier = mock(ProjectionSnapshotVerifier.class);
        when(verifier.verify(any(), any()))
                .thenReturn(new ProjectionVerificationSnapshots(HEALTHY, HEALTHY));
        when(verifier.current(any(), any())).thenReturn(HEALTHY);
        service = new ProjectionAdministrationService(
                administration,
                repository,
                verifier,
                new SpringTransactionExecutor(transactionManager),
                () -> NOW);
    }

    @Test
    void persistsCompleteLifecycleWithReceiptsDomainEventsOutboxAndAudit() {
        ProjectionAdministrationCommandId startCommandId =
                ProjectionAdministrationCommandId.generate();
        ProjectionAdministrationResult started = service.start(startCommand(startCommandId, 0));

        ProjectionAdministrationResult validated = service.validate(
                new ValidateProjectionGenerationCommand(
                        ProjectionAdministrationCommandId.generate(),
                        organizationId,
                        PROJECTION_NAME,
                        DEFINITION_VERSION,
                        started.generation(),
                        started.rebuildJobId(),
                        0,
                        0,
                        access,
                        confirmation(
                                ProjectionAdministrationAction.VALIDATE_GENERATION,
                                Optional.of(started.generation()))));
        assertEquals(ProjectionGenerationStatus.VALIDATING, validated.generationStatus());

        ProjectionAdministrationResult switched = service.switchGeneration(
                new SwitchProjectionGenerationCommand(
                        ProjectionAdministrationCommandId.generate(),
                        organizationId,
                        PROJECTION_NAME,
                        DEFINITION_VERSION,
                        ProjectionGeneration.FIRST,
                        started.generation(),
                        started.rebuildJobId(),
                        0,
                        0,
                        1,
                        1,
                        access,
                        confirmation(
                                ProjectionAdministrationAction.SWITCH_GENERATION,
                                Optional.of(started.generation()))));
        assertEquals(ProjectionGenerationStatus.ACTIVE, switched.generationStatus());
        assertEquals(1L, switched.pointerVersion().orElseThrow());

        ProjectionAdministrationResult second = service.start(
                startCommand(ProjectionAdministrationCommandId.generate(), 1));
        ProjectionAdministrationResult cancelled = service.terminate(
                new TerminateProjectionRebuildCommand(
                        ProjectionAdministrationCommandId.generate(),
                        organizationId,
                        PROJECTION_NAME,
                        second.generation(),
                        second.rebuildJobId(),
                        0,
                        0,
                        ProjectionAdministrationAction.CANCEL_REBUILD,
                        Optional.empty(),
                        access,
                        confirmation(
                                ProjectionAdministrationAction.CANCEL_REBUILD,
                                Optional.of(second.generation()))));

        assertEquals(ProjectionGenerationStatus.CANCELLED, cancelled.generationStatus());
        assertEquals(5, count("projection_command_receipt"));
        assertEquals(5, count("domain_event"));
        assertEquals(5, count("outbox_event"));
        assertEquals(5, count("audit_event"));
        assertEquals(5, count("event_consumer_receipt"));
        assertEquals("RETIRED", generationStatus(1));
        assertEquals("ACTIVE", generationStatus(2));
        assertEquals("CANCELLED", generationStatus(3));
        assertEquals("COMPLETED", rebuildStatus(started.rebuildJobId()));
        assertEquals("CANCELLED", rebuildStatus(second.rebuildJobId()));
        assertTrue(repository.findReceipt(organizationId, startCommandId).isPresent());
    }

    @Test
    void exactStartCommandReplayDoesNotAppendAnotherFact() {
        ProjectionAdministrationCommandId commandId = ProjectionAdministrationCommandId.generate();
        StartProjectionRebuildCommand command = startCommand(commandId, 0);

        ProjectionAdministrationResult first = service.start(command);
        ProjectionAdministrationResult replay = service.start(command);

        assertEquals(first, replay);
        assertEquals(1, count("projection_command_receipt"));
        assertEquals(1, count("domain_event"));
        assertEquals(1, count("audit_event"));
    }

    private StartProjectionRebuildCommand startCommand(
            ProjectionAdministrationCommandId commandId, long pointerVersion) {
        return new StartProjectionRebuildCommand(
                commandId,
                organizationId,
                PROJECTION_NAME,
                DEFINITION_VERSION,
                pointerVersion,
                access,
                confirmation(ProjectionAdministrationAction.START_REBUILD, Optional.empty()));
    }

    private ProjectionStrongConfirmation confirmation(
            ProjectionAdministrationAction action, Optional<ProjectionGeneration> generation) {
        return ProjectionStrongConfirmation.confirm(action, PROJECTION_NAME, generation);
    }

    private void seedRegistry() {
        // The deferred V27 invariant validates Pointer and ACTIVE Generation together at commit.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update(
                    """
                    INSERT INTO crewscope.projection_definition (
                        projection_name, definition_version, projection_schema_version,
                        canonical_encoder, validator
                    ) VALUES (?, 1, 1, 'test.canonical-v1', 'test.validator-v1')
                    """,
                    PROJECTION_NAME.value());
            jdbc.update(
                    """
                    INSERT INTO crewscope.projection_generation (
                        organization_id, projection_name, generation, definition_version,
                        status, fencing_token, version, created_at, updated_at
                    ) VALUES (?, ?, 1, 1, 'ACTIVE', 1, 0, ?, ?)
                    """,
                    organizationId.value(), PROJECTION_NAME.value(),
                    NOW.toOffsetDateTime(), NOW.toOffsetDateTime());
            jdbc.update(
                    """
                    INSERT INTO crewscope.projection_pointer (
                        organization_id, projection_name, active_generation, version, updated_at
                    ) VALUES (?, ?, 1, 0, ?)
                    """,
                    organizationId.value(), PROJECTION_NAME.value(), NOW.toOffsetDateTime());
        });
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM crewscope." + table, Integer.class);
    }

    private String generationStatus(long generation) {
        return jdbc.queryForObject(
                """
                SELECT status FROM crewscope.projection_generation
                WHERE organization_id = ? AND projection_name = ? AND generation = ?
                """,
                String.class, organizationId.value(), PROJECTION_NAME.value(), generation);
    }

    private String rebuildStatus(ProjectionRebuildJobId jobId) {
        return jdbc.queryForObject(
                "SELECT status FROM crewscope.projection_rebuild_job WHERE id = ?",
                String.class, jobId.value());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {}
}
