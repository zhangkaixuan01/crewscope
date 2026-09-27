package io.crewscope.application.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
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
        InboxDispositionOutcome read = change(
                ownerPrincipal, currentItem.id(), InboxDispositionStatus.READ, 0);
        InboxDispositionOutcome archived = change(
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
        InboxDispositionOutcome read = change(
                ownerPrincipal, currentItem.id(), InboxDispositionStatus.READ, 0);

        assertThrows(
                OptimisticLockConflictException.class,
                () -> change(
                        ownerPrincipal, currentItem.id(), InboxDispositionStatus.ACTED, 0));
        InboxDispositionOutcome same = change(
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
        InboxDispositionOutcome acted = change(
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

    @Test
    void unmarkingAMissingRowIsTheVersionZeroNoOp() {
        InboxDispositionOutcome outcome = change(
                ownerPrincipal, currentItem.id(), InboxDispositionStatus.UNREAD, 0);

        assertEquals(InboxDispositionStatus.UNREAD, outcome.status());
        assertEquals(0, outcome.version());
        assertEquals(0, dispositions.saveCount);
        assertTrue(dispositions.find(
                ORGANIZATION_ID, TEAM_ID, ownerMember.id(), currentItem.id()).isEmpty());
    }

    @Test
    void unmarkingAMissingRowWithAStaleExpectedVersionConflicts() {
        // create() rejects a non-zero expectedVersion against the implicit version-0 row, so the
        // no-op branch must too: a silent 202 here would mask a client acting on stale state.
        assertThrows(
                OptimisticLockConflictException.class,
                () -> change(
                        ownerPrincipal, currentItem.id(), InboxDispositionStatus.UNREAD, 7));

        assertEquals(0, dispositions.saveCount);
        assertTrue(dispositions.find(
                ORGANIZATION_ID, TEAM_ID, ownerMember.id(), currentItem.id()).isEmpty());
    }

    @Test
    void unmarkingKeepsAPersistedUnreadRowAtAPositiveVersion() {
        InboxDispositionOutcome read = change(
                ownerPrincipal, currentItem.id(), InboxDispositionStatus.READ, 0);
        InboxDispositionOutcome unread = change(
                ownerPrincipal, currentItem.id(), InboxDispositionStatus.UNREAD, read.version());

        assertEquals(InboxDispositionStatus.UNREAD, unread.status());
        assertEquals(read.version() + 1, unread.version());
        assertEquals(2, dispositions.saveCount);
        InboxItemView merged = InboxItemView.merge(
                currentItem,
                dispositions.find(
                        ORGANIZATION_ID, TEAM_ID, ownerMember.id(), currentItem.id()));
        assertEquals(InboxDispositionStatus.UNREAD, merged.dispositionStatus());
        assertEquals(unread.version(), merged.dispositionVersion());
    }

    @Test
    void restoringAnArchiveLandsOnRead() {
        InboxDispositionOutcome read = change(
                ownerPrincipal, currentItem.id(), InboxDispositionStatus.READ, 0);
        InboxDispositionOutcome archived = change(
                ownerPrincipal, currentItem.id(), InboxDispositionStatus.ARCHIVED, read.version());
        InboxDispositionOutcome restored = change(
                ownerPrincipal, currentItem.id(), InboxDispositionStatus.READ, archived.version());

        assertEquals(InboxDispositionStatus.READ, restored.status());
        assertEquals(archived.version() + 1, restored.version());
        assertEquals(3, dispositions.saveCount);
    }

    @Test
    void unmarkingAnArchiveAppliesRestoreThenUnmarkAsOneCommand() {
        InboxDispositionOutcome read = change(
                ownerPrincipal, currentItem.id(), InboxDispositionStatus.READ, 0);
        InboxDispositionOutcome archived = change(
                ownerPrincipal, currentItem.id(), InboxDispositionStatus.ARCHIVED, read.version());
        int savesBefore = dispositions.saveCount;

        InboxDispositionOutcome outcome = change(
                ownerPrincipal, currentItem.id(), InboxDispositionStatus.UNREAD, archived.version());

        assertEquals(InboxDispositionStatus.UNREAD, outcome.status());
        assertEquals(archived.version() + 2, outcome.version());
        assertEquals(savesBefore + 2, dispositions.saveCount);
        InboxDisposition persisted = dispositions.find(
                ORGANIZATION_ID, TEAM_ID, ownerMember.id(), currentItem.id()).orElseThrow();
        assertEquals(InboxDispositionStatus.UNREAD, persisted.status());
        assertEquals(outcome.version(), persisted.version());
    }

    @Test
    void aFailedSecondStepLeavesTheArchiveIntactForRetry() {
        InboxDispositionOutcome read = change(
                ownerPrincipal, currentItem.id(), InboxDispositionStatus.READ, 0);
        InboxDispositionOutcome archived = change(
                ownerPrincipal, currentItem.id(), InboxDispositionStatus.ARCHIVED, read.version());
        int savesBefore = dispositions.saveCount;
        dispositions.failNextSave = true;

        // The two-step write fails mid-sequence: the whole command must surface the failure
        // (the real repository rolls both writes back in one transaction) instead of returning
        // a receipt for a half-applied sequence, and the retry must complete cleanly.
        assertThrows(
                IllegalStateException.class,
                () -> change(
                        ownerPrincipal, currentItem.id(), InboxDispositionStatus.UNREAD,
                        archived.version()));
        dispositions.failNextSave = false;
        InboxDispositionOutcome retried = change(
                ownerPrincipal, currentItem.id(), InboxDispositionStatus.UNREAD,
                archived.version());

        assertEquals(InboxDispositionStatus.UNREAD, retried.status());
        assertEquals(archived.version() + 2, retried.version());
        assertEquals(savesBefore + 2, dispositions.saveCount);
    }

    @Test
    void archivingAPersistedUnreadRowKeepsThePositiveVersion() {
        InboxDispositionOutcome read = change(
                ownerPrincipal, currentItem.id(), InboxDispositionStatus.READ, 0);
        InboxDispositionOutcome unread = change(
                ownerPrincipal, currentItem.id(), InboxDispositionStatus.UNREAD, read.version());
        InboxDispositionOutcome archived = change(
                ownerPrincipal, currentItem.id(), InboxDispositionStatus.ARCHIVED,
                unread.version());

        assertEquals(InboxDispositionStatus.ARCHIVED, archived.status());
        assertEquals(unread.version() + 1, archived.version());
    }

    @Test
    void archivedRowRejectsDirectReadAndActedCommands() {
        InboxDispositionOutcome read = change(
                ownerPrincipal, currentItem.id(), InboxDispositionStatus.READ, 0);
        InboxDispositionOutcome archived = change(
                ownerPrincipal, currentItem.id(), InboxDispositionStatus.ARCHIVED, read.version());

        assertThrows(
                InvalidStateTransitionException.class,
                () -> change(
                        ownerPrincipal, currentItem.id(), InboxDispositionStatus.ACTED,
                        archived.version()));
        // READ then ARCHIVED each wrote once; the rejected ACTED wrote nothing.
        assertEquals(2, dispositions.saveCount);
    }

    private InboxDispositionOutcome change(
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
        private boolean failNextSave;

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
            if (failNextSave) {
                failNextSave = false;
                throw new IllegalStateException("simulated write failure");
            }
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
