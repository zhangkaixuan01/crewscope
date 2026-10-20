package io.crewscope.application.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamStatus;
import io.crewscope.domain.team.UninitializedTeam;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * M13b-A03 contract: the switch board itself is a configuration constant, so everything this
 * test pins is the guard — the {@code KnowledgeIndexControlService} read-guard shape
 * (organization USER, READY+ACTIVE Team, ACTIVE member) wrapped in one read transaction.
 */
final class KnowledgeSwitchQueryServiceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-20T08:00:00Z");
    private static final KnowledgeSwitchBoard BOARD =
            new KnowledgeSwitchBoard(true, true, false, true, false, false);

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal actor =
            Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.organization(organizationId),
                    PrincipalType.USER,
                    Optional.empty(),
                    "Owner",
                    Optional.empty(),
                    PrincipalVisibility.ORGANIZATION,
                    NOW);
    private final TeamInitialization initialization =
            TeamInitialization.create(actor, "Platform", NOW);
    private final TeamId teamId = initialization.team().id();
    private final Store store = new Store();

    @Test
    void activeMemberReadsTheBoardInsideOneTransaction() {
        RecordingTransactions transactions = new RecordingTransactions();
        KnowledgeSwitchQueryService service =
                new KnowledgeSwitchQueryService(store, store, transactions, BOARD);

        assertSame(BOARD, service.switches(access(), organizationId, teamId));
        assertEquals(1, transactions.requiredCalls, "the guard and the read share one transaction");
    }

    @Test
    void nonMemberIsDenied() {
        store.members = List.of();
        KnowledgeSwitchQueryService service = service();

        PolicyDeniedException denied =
                assertThrows(
                        PolicyDeniedException.class,
                        () -> service.switches(access(), organizationId, teamId));
        assertEquals("access this Team's knowledge", denied.error().details().get("action"));
    }

    @Test
    void foreignOrganizationActorIsDeniedBeforeAnyTeamLookup() {
        OrganizationId foreign = OrganizationId.generate();
        store.lookups = 0;
        KnowledgeSwitchQueryService service = service();

        PolicyDeniedException denied =
                assertThrows(
                        PolicyDeniedException.class,
                        () -> service.switches(access(), foreign, teamId));
        assertEquals("act in this Organization", denied.error().details().get("action"));
        assertEquals(0, store.lookups, "the organization guard fires before the Team lookup");
    }

    @Test
    void unknownTeamIsNotFound() {
        KnowledgeSwitchQueryService service = service();

        assertThrows(
                AggregateNotFoundException.class,
                () -> service.switches(access(), organizationId, TeamId.generate()));
    }

    @Test
    void uninitializedTeamIsRejected() {
        store.uninitialized = Optional.of(
                new UninitializedTeam(
                        teamId, organizationId, "Platform",
                        TeamStatus.ACTIVE, 0, AuditMetadata.createdBy(actor.id(), NOW)));
        KnowledgeSwitchQueryService service = service();

        DomainValidationException rejected =
                assertThrows(
                        DomainValidationException.class,
                        () -> service.switches(access(), organizationId, teamId));
        assertEquals("team.initializationStatus", rejected.error().details().get("field"));
    }

    // ------------------------------------------------------------------ fixtures

    private KnowledgeSwitchQueryService service() {
        return new KnowledgeSwitchQueryService(store, store, new DirectTransactions(), BOARD);
    }

    private TeamAccessContext access() {
        return new TeamAccessContext(actor, false);
    }

    private static final class DirectTransactions implements TransactionExecutor {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    }

    private static final class RecordingTransactions implements TransactionExecutor {
        private int requiredCalls;

        @Override
        public <T> T required(Supplier<T> operation) {
            requiredCalls += 1;
            return operation.get();
        }
    }

    /** The Team/membership pair the read guard consults, trimmed to this slice. */
    private final class Store implements TeamRepository, TeamMembershipQuery {
        private List<TeamMember> members = List.of(initialization.ownerMember());
        private Optional<UninitializedTeam> uninitialized = Optional.empty();
        private int lookups;

        @Override
        public Team create(Team team) {
            return team;
        }

        @Override
        public Optional<Team> findById(OrganizationId organization, TeamId id) {
            lookups += 1;
            return Optional.of(initialization.team())
                    .filter(team -> team.organizationId().equals(organization)
                            && team.id().equals(id));
        }

        @Override
        public Optional<UninitializedTeam> findUninitializedById(
                OrganizationId organization, TeamId id) {
            return uninitialized.filter(
                    value -> value.organizationId().equals(organization)
                            && value.id().equals(id));
        }

        @Override
        public List<TeamMember> findByTeam(OrganizationId organization, TeamId team) {
            return members;
        }
    }
}
