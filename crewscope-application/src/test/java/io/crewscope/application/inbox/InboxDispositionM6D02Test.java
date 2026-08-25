package io.crewscope.application.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.inbox.InboxCloseReason;
import io.crewscope.domain.inbox.InboxDisposition;
import io.crewscope.domain.inbox.InboxDispositionStatus;
import io.crewscope.domain.inbox.InboxItem;
import io.crewscope.domain.inbox.InboxItemId;
import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.inbox.InboxPriority;
import io.crewscope.domain.inbox.InboxSource;
import io.crewscope.domain.inbox.InboxSourceKey;
import io.crewscope.domain.inbox.InboxSourceRevision;
import io.crewscope.domain.inbox.InboxSourceType;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamScope;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InboxDispositionM6D02Test {

    private static final OrganizationId ORGANIZATION_ID =
            OrganizationId.from("00000000-0000-0000-0000-000000000501");
    private static final TeamId TEAM_ID =
            TeamId.from("00000000-0000-0000-0000-000000000502");
    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-25T11:00:00Z");
    private static final ProjectionName PROJECTION_NAME = new ProjectionName("member-inbox");

    private Principal ownerPrincipal;
    private Principal otherPrincipal;
    private TeamMember ownerMember;
    private TeamMember otherMember;
    private InboxItem currentItem;
    private InMemoryDispositionRepository dispositions;
    private MutableItemQuery items;
    private InboxDispositionApplicationService service;
    private AtomicInteger transactions;

    @BeforeEach
    void setUp() {
        ownerPrincipal = principal("00000000-0000-0000-0000-000000000503", "Owner");
        otherPrincipal = principal("00000000-0000-0000-0000-000000000504", "Other");
        ownerMember = member(
                "00000000-0000-0000-0000-000000000505", ownerPrincipal);
        otherMember = member(
                "00000000-0000-0000-0000-000000000506", otherPrincipal);
        currentItem = item(ownerMember.id(), ProjectionGeneration.FIRST);
        dispositions = new InMemoryDispositionRepository();
        items = new MutableItemQuery(currentItem);
        transactions = new AtomicInteger();
        TeamMembershipQuery memberships = (organizationId, teamId) ->
                List.of(ownerMember, otherMember);
        TransactionExecutor transactionExecutor = new TransactionExecutor() {
            @Override
            public <T> T required(Supplier<T> operation) {
                transactions.incrementAndGet();
                return operation.get();
            }
        };
        TimeProvider timeProvider = () -> NOW;
        service = new InboxDispositionApplicationService(
                items, dispositions, memberships, transactionExecutor, timeProvider);
    }

    @Test
    void rebuildKeepsArchivedDispositionOutsideProjectionGeneration() {
        InboxDisposition read = change(
                ownerPrincipal, currentItem.id(), InboxDispositionStatus.READ, 0);
        InboxDisposition archived = change(
                ownerPrincipal, currentItem.id(), InboxDispositionStatus.ARCHIVED, read.version());

        InboxItem rebuilt = item(ownerMember.id(), new ProjectionGeneration(2));
        items.current = rebuilt;
        InboxItemView merged = InboxItemView.merge(
                rebuilt,
                dispositions.find(
                        ORGANIZATION_ID, TEAM_ID, ownerMember.id(), rebuilt.id()));

        assertEquals(currentItem.id(), rebuilt.id());
        assertEquals(InboxDispositionStatus.ARCHIVED, merged.dispositionStatus());
        assertEquals(archived.version(), merged.dispositionVersion());
        assertEquals(2, dispositions.saveCount);
        assertEquals(2, transactions.get());
    }

    @Test
    void staleEtagFailsAndExactCurrentStateRetryDoesNotWriteAgain() {
        InboxDisposition read = change(
                ownerPrincipal, currentItem.id(), InboxDispositionStatus.READ, 0);

        assertThrows(
                OptimisticLockConflictException.class,
                () -> change(
                        ownerPrincipal, currentItem.id(), InboxDispositionStatus.ACTED, 0));
        InboxDisposition same = change(
                ownerPrincipal,
                currentItem.id(),
                InboxDispositionStatus.READ,
                read.version());

        assertEquals(read.version(), same.version());
        assertEquals(1, dispositions.saveCount);
    }

    @Test
    void anotherMemberCannotChangeTheTargetDisposition() {
        assertThrows(
                PolicyDeniedException.class,
                () -> change(
                        otherPrincipal,
                        currentItem.id(),
                        InboxDispositionStatus.ARCHIVED,
                        0));

        assertEquals(0, dispositions.saveCount);
    }

    @Test
    void closingCurrentSourceDoesNotEraseMemberDisposition() {
        InboxDisposition acted = change(
                ownerPrincipal, currentItem.id(), InboxDispositionStatus.ACTED, 0);
        items.current = currentItem.close(InboxCloseReason.REVIEW_COMPLETED, NOW);

        InboxItemView merged = InboxItemView.merge(
                items.current,
                dispositions.find(
                        ORGANIZATION_ID, TEAM_ID, ownerMember.id(), currentItem.id()));

        assertEquals(InboxDispositionStatus.ACTED, merged.dispositionStatus());
        assertEquals(acted.version(), merged.dispositionVersion());
        assertEquals(InboxCloseReason.REVIEW_COMPLETED,
                merged.item().source().closeReason().orElseThrow());
    }

    private InboxDisposition change(
            Principal actor,
            InboxItemId itemId,
            InboxDispositionStatus status,
            long expectedVersion) {
        return service.change(
                new TeamAccessContext(actor, false),
                ORGANIZATION_ID,
                TEAM_ID,
                itemId,
                new ChangeInboxDispositionCommand(status, expectedVersion));
    }

    private static Principal principal(String id, String displayName) {
        return Principal.create(
                PrincipalId.from(id),
                PrincipalScope.team(ORGANIZATION_ID, TEAM_ID),
                PrincipalType.USER,
                Optional.empty(),
                displayName,
                Optional.empty(),
                PrincipalVisibility.TEAM,
                NOW);
    }

    private static TeamMember member(String id, Principal principal) {
        return TeamMember.join(
                TeamMemberId.from(id),
                new TeamScope(ORGANIZATION_ID, TEAM_ID),
                principal,
                TeamJoinMethod.BOOTSTRAP,
                NOW);
    }

    private static InboxItem item(TeamMemberId memberId, ProjectionGeneration generation) {
        InboxSource source = InboxSource.open(
                new InboxSourceKey(
                        ORGANIZATION_ID,
                        memberId,
                        InboxItemType.REVIEW,
                        InboxSourceType.REVIEW_REQUEST,
                        UUID.fromString("00000000-0000-0000-0000-000000000507"),
                        new InboxSourceRevision(7)),
                InboxPriority.HIGH,
                Optional.of(UtcTimestamp.parse("2026-08-26T11:00:00Z")),
                NOW);
        return InboxItem.project(
                TEAM_ID, PROJECTION_NAME, generation, SchemaVersion.V1, source);
    }

    private static final class MutableItemQuery implements InboxItemQueryPort {

        private InboxItem current;

        private MutableItemQuery(InboxItem current) {
            this.current = current;
        }

        @Override
        public Optional<InboxItem> findCurrent(
                OrganizationId organizationId, TeamId teamId, InboxItemId inboxItemId) {
            return current.id().equals(inboxItemId) ? Optional.of(current) : Optional.empty();
        }
    }

    private static final class InMemoryDispositionRepository
            implements InboxDispositionRepository {

        private final Map<InboxItemId, InboxDisposition> values = new HashMap<>();
        private int saveCount;

        @Override
        public Optional<InboxDisposition> find(
                OrganizationId organizationId,
                TeamId teamId,
                TeamMemberId memberId,
                InboxItemId inboxItemId) {
            return Optional.ofNullable(values.get(inboxItemId));
        }

        @Override
        public void save(InboxDisposition disposition, long expectedVersion) {
            long actualVersion = Optional.ofNullable(values.get(disposition.inboxItemId()))
                    .map(InboxDisposition::version)
                    .orElse(0L);
            if (actualVersion != expectedVersion) {
                throw new OptimisticLockConflictException(
                        "InboxDisposition",
                        disposition.inboxItemId(),
                        expectedVersion,
                        actualVersion);
            }
            values.put(disposition.inboxItemId(), disposition);
            saveCount++;
        }
    }
}
