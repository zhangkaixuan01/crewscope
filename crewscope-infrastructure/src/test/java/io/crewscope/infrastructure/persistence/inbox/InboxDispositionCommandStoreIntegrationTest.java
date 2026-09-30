package io.crewscope.infrastructure.persistence.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.inbox.ChangeInboxDispositionCommand;
import io.crewscope.application.inbox.InboxApplicationService;
import io.crewscope.application.inbox.InboxDispositionApplicationService;
import io.crewscope.application.inbox.InboxDispositionCommandService;
import io.crewscope.application.inbox.InboxDispositionOutcome;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.inbox.InboxDispositionStatus;
import io.crewscope.domain.inbox.InboxItemId;
import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.inbox.InboxSourceKey;
import io.crewscope.domain.inbox.InboxSourceRevision;
import io.crewscope.domain.inbox.InboxSourceType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamScope;
import io.crewscope.infrastructure.persistence.command.JdbcCommandReceiptStore;
import io.crewscope.infrastructure.persistence.event.JdbcDomainEventStore;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.transaction.SpringTransactionExecutor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M9b-Q02 regression: the durable Inbox disposition command must complete against migrated
 * PostgreSQL. The V5 command_receipt foreign key requires every completed reservation to
 * reference one committed domain_event row, so the command appends its own Generation-independent
 * fact event — the first real-stack run of the PUT caught the previous synthetic fact id failing
 * that constraint with a 500.
 *
 * <p>Also pins the §5.1 idempotent replay (same key returns the committed receipt without a
 * second fact) and that no outbox row is queued, because unmark and restore must never
 * re-notify anyone.
 */
@SpringBootTest(
        classes = InboxDispositionCommandStoreIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=crewscope",
            "spring.jpa.open-in-view=false"
        })
class InboxDispositionCommandStoreIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-09-27T10:05:40Z");
    private static final String PROJECTION_NAME = "member-inbox";

    @Autowired
    private JdbcInboxRepositoryAdapter inboxRepository;

    @Autowired
    private CommandReceiptStore receiptStore;

    @Autowired
    private DomainEventStore eventStore;

    @Autowired
    private TransactionExecutor transactions;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    private OrganizationId organizationId;
    private TeamId teamId;
    private TeamMemberId memberId;
    private Principal principal;
    private TeamAccessContext access;
    private UUID inboxItemId;

    @BeforeEach
    void seedScope() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        organizationId = OrganizationId.generate();
        teamId = TeamId.generate();
        PrincipalId principalId = PrincipalId.generate();
        memberId = TeamMemberId.generate();

        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, ?, 'ACTIVE')",
                organizationId.value(), "Q02 Inbox Command Organization");
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', 'Q02 Inbox Member', 'ORGANIZATION', 'ACTIVE')
                """,
                principalId.value(), organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) "
                        + "VALUES (?, ?, 'Q02 Inbox Command Team', 'ACTIVE')",
                teamId.value(), organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.team_member "
                        + "(id, organization_id, team_id, user_principal_id, status, join_method, joined_at) "
                        + "VALUES (?, ?, ?, ?, 'ACTIVE', 'IMPORT', ?)",
                memberId.value(), organizationId.value(), teamId.value(), principalId.value(),
                NOW.toOffsetDateTime());

        jdbc.update(
                """
                INSERT INTO crewscope.projection_definition (
                    projection_name, definition_version, projection_schema_version,
                    canonical_encoder, validator
                ) VALUES (?, 1, 1, 'inbox.canonical-v1', 'inbox.expected-v1')
                ON CONFLICT (projection_name, definition_version) DO NOTHING
                """,
                PROJECTION_NAME);
        // The generation/pointer pair guards a write path this seed does not reproduce; the read
        // model's own tests suspend the triggers the same way while seeding join inputs.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update(
                    """
                    INSERT INTO crewscope.projection_generation (
                        organization_id, projection_name, generation, definition_version,
                        status, fencing_token, version, created_at, updated_at
                    ) VALUES (?, ?, 1, 1, 'ACTIVE', 1, 0, ?, ?)
                    ON CONFLICT (organization_id, projection_name, generation) DO NOTHING
                    """,
                    organizationId.value(), PROJECTION_NAME,
                    NOW.toOffsetDateTime(), NOW.toOffsetDateTime());
            jdbc.update(
                    """
                    INSERT INTO crewscope.projection_pointer (
                        organization_id, projection_name, active_generation, version, updated_at
                    ) VALUES (?, ?, 1, 0, ?)
                    ON CONFLICT (organization_id, projection_name) DO NOTHING
                    """,
                    organizationId.value(), PROJECTION_NAME, NOW.toOffsetDateTime());
        });

        // A notification row keeps detail() free of work-item joins; the command only revalidates
        // that the caller owns the item. The read side revalidates that every inbox_item_id is the
        // canonical derivation of its source key, so the seed derives it the same way.
        UUID sourceId = UUID.randomUUID();
        inboxItemId = InboxItemId.fromSource(new InboxSourceKey(
                organizationId, memberId, InboxItemType.EXCEPTION,
                InboxSourceType.NOTIFICATION_DELIVERY, sourceId,
                InboxSourceRevision.INITIAL)).value();
        jdbc.update(
                """
                INSERT INTO crewscope.inbox_item (
                    organization_id, team_id, member_id, projection_name, generation,
                    inbox_item_id, projection_schema_version, item_type, source_type,
                    source_id, source_revision, priority, deadline, opened_at, source_status
                ) VALUES (?, ?, ?, ?, 1, ?, 1, 'EXCEPTION', 'NOTIFICATION_DELIVERY',
                          ?, 0, 'NORMAL', NULL, ?, 'OPEN')
                """,
                organizationId.value(), teamId.value(), memberId.value(), PROJECTION_NAME,
                inboxItemId, sourceId, NOW.toOffsetDateTime());

        principal = Principal.create(
                principalId,
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Q02 Inbox Member",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        access = new TeamAccessContext(principal, false);
    }

    /**
     * The completion path itself: reserve, transition, append one fact, complete. The receipt's
     * domain_event_id must exist in domain_event — exactly what the real stack 500'd on before.
     */
    @Test
    void completesAgainstMigratedPostgresqlReferencingOneAppendedFact() {
        CommandExecution<InboxDispositionOutcome> execution = commands().change(
                commandContext("q02-inbox-read-1"),
                organizationId,
                teamId,
                itemId(),
                new ChangeInboxDispositionCommand(InboxDispositionStatus.READ, 0));
        InboxDispositionOutcome outcome = execution.result().orElseThrow();

        assertFalse(execution.replayed());
        assertEquals(InboxDispositionStatus.READ, outcome.status());
        assertEquals(1, outcome.version());

        UUID referencedEvent = jdbc.queryForObject(
                """
                SELECT r.domain_event_id FROM crewscope.command_receipt r
                WHERE r.organization_id = ? AND r.idempotency_key = ?
                  AND r.status = 'COMPLETED'
                """,
                UUID.class,
                organizationId.value(), "q02-inbox-read-1");
        assertEquals(execution.receipt().domainEventId(), referencedEvent);

        // The exact constraint the real stack violated: the referenced fact must exist.
        assertEquals(1, jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM crewscope.command_receipt r
                JOIN crewscope.domain_event e
                  ON e.organization_id = r.organization_id AND e.event_id = r.domain_event_id
                WHERE r.organization_id = ? AND r.idempotency_key = ?
                """,
                Integer.class,
                organizationId.value(), "q02-inbox-read-1").intValue());
        assertEquals("INBOX_DISPOSITION_CHANGED", jdbc.queryForObject(
                "SELECT event_type FROM crewscope.domain_event WHERE event_id = ?",
                String.class, referencedEvent));

        // The authority row committed the transition the page will read back.
        assertEquals(
                "READ",
                jdbc.queryForObject(
                        "SELECT status FROM crewscope.inbox_disposition WHERE inbox_item_id = ?",
                        String.class, inboxItemId));

        // §5.1: unmark and restore never re-notify, so no outbox row is queued for the fact.
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.outbox_event WHERE domain_event_id = ?",
                Integer.class, referencedEvent).intValue());
    }

    /** The same key replays the committed receipt without appending a second fact. */
    @Test
    void replaysTheCommittedReceiptWithoutASecondFact() {
        InboxDispositionCommandService commands = commands();
        CommandExecution<InboxDispositionOutcome> first = commands.change(
                commandContext("q02-inbox-replay-1"), organizationId, teamId, itemId(),
                new ChangeInboxDispositionCommand(InboxDispositionStatus.READ, 0));
        CommandExecution<InboxDispositionOutcome> replay = commands.change(
                commandContext("q02-inbox-replay-1"), organizationId, teamId, itemId(),
                new ChangeInboxDispositionCommand(InboxDispositionStatus.READ, 0));

        assertEquals(true, replay.replayed());
        assertEquals(first.receipt(), replay.receipt());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.domain_event WHERE event_type = "
                        + "'INBOX_DISPOSITION_CHANGED'",
                Integer.class).intValue());
    }

    private InboxItemId itemId() {
        return InboxItemId.from(inboxItemId.toString());
    }

    private TeamCommandContext commandContext(String key) {
        return new TeamCommandContext(
                access, IdempotencyKey.from(key), UUID.randomUUID(), Optional.empty());
    }

    private InboxDispositionCommandService commands() {
        TeamMember member = TeamMember.join(
                memberId, new TeamScope(organizationId, teamId), principal,
                TeamJoinMethod.IMPORT, NOW);
        TeamMembershipQuery membershipQuery = (organization, team) -> List.of(member);
        WorkItemAccessPolicy accessPolicy = mock(WorkItemAccessPolicy.class);
        when(accessPolicy.requireVisibleTeamMember(access, organizationId, teamId))
                .thenReturn(member);
        InboxApplicationService authorization =
                new InboxApplicationService(inboxRepository, accessPolicy);
        InboxDispositionApplicationService dispositions = new InboxDispositionApplicationService(
                inboxRepository, inboxRepository, membershipQuery, transactions, fixedTime());
        return new InboxDispositionCommandService(
                authorization, dispositions, receiptStore, eventStore, transactions, fixedTime());
    }

    private TimeProvider fixedTime() {
        return TimeProvider.from(Clock.fixed(
                Instant.parse("2026-09-27T10:05:40.123456789Z"), ZoneOffset.UTC));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
        JdbcInboxRepositoryAdapter.class,
        JdbcDomainEventStore.class,
        JdbcCommandReceiptStore.class,
        SpringTransactionExecutor.class
    })
    static class TestApplication {}
}
