package io.crewscope.application.inbox;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.inbox.InboxDisposition;
import io.crewscope.domain.inbox.InboxDispositionStatus;
import io.crewscope.domain.inbox.InboxItem;
import io.crewscope.domain.inbox.InboxItemId;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMember;
import java.util.Objects;
import java.util.Optional;

/** Applies member-only Inbox disposition commands without mutating replaceable source rows. */
public final class InboxDispositionApplicationService {

    private static final String DISPOSE_OWN_INBOX = "change this Inbox disposition";

    private final InboxItemQueryPort itemQueryPort;
    private final InboxDispositionRepository dispositionRepository;
    private final TeamMembershipQuery membershipQuery;
    private final TransactionExecutor transactionExecutor;
    private final TimeProvider timeProvider;

    public InboxDispositionApplicationService(
            InboxItemQueryPort itemQueryPort,
            InboxDispositionRepository dispositionRepository,
            TeamMembershipQuery membershipQuery,
            TransactionExecutor transactionExecutor,
            TimeProvider timeProvider) {
        this.itemQueryPort = Objects.requireNonNull(itemQueryPort, "itemQueryPort");
        this.dispositionRepository =
                Objects.requireNonNull(dispositionRepository, "dispositionRepository");
        this.membershipQuery = Objects.requireNonNull(membershipQuery, "membershipQuery");
        this.transactionExecutor =
                Objects.requireNonNull(transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public InboxDispositionOutcome change(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            InboxItemId inboxItemId,
            ChangeInboxDispositionCommand command) {
        TeamAccessContext trusted = Objects.requireNonNull(context, "context");
        OrganizationId requiredOrganization =
                Objects.requireNonNull(organizationId, "organizationId");
        TeamId requiredTeam = Objects.requireNonNull(teamId, "teamId");
        InboxItemId requiredItemId = Objects.requireNonNull(inboxItemId, "inboxItemId");
        ChangeInboxDispositionCommand requiredCommand =
                Objects.requireNonNull(command, "command");
        return transactionExecutor.required(() -> changeInTransaction(
                trusted,
                requiredOrganization,
                requiredTeam,
                requiredItemId,
                requiredCommand));
    }

    private InboxDispositionOutcome changeInTransaction(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            InboxItemId inboxItemId,
            ChangeInboxDispositionCommand command) {
        TeamMember member = requireActiveMember(context.actor(), organizationId, teamId);
        InboxItem item = itemQueryPort.findCurrent(organizationId, teamId, inboxItemId)
                .filter(value -> value.organizationId().equals(organizationId))
                .filter(value -> value.teamId().equals(teamId))
                .orElseThrow(() -> new AggregateNotFoundException("InboxItem", inboxItemId));
        if (!item.memberId().equals(member.id())) {
            throw new PolicyDeniedException(DISPOSE_OWN_INBOX);
        }

        Optional<InboxDisposition> committed = dispositionRepository.find(
                organizationId, teamId, member.id(), inboxItemId);
        if (committed.isEmpty()) {
            // A missing row already is version-0 UNREAD; unmarking it changes nothing and must not
            // create a row (contract §5.1 replay row). create() below rejects a non-zero
            // expectedVersion, so the no-op branch mirrors it: a missing row only ever exposes
            // version 0, and a stale non-zero expectation must conflict rather than mask it.
            if (command.targetStatus() == InboxDispositionStatus.UNREAD) {
                if (command.expectedVersion() != 0) {
                    throw new OptimisticLockConflictException(
                            "InboxDisposition", inboxItemId, command.expectedVersion(), 0);
                }
                return InboxDispositionOutcome.missingRowUnread();
            }
            InboxDisposition created = InboxDisposition.create(
                    item,
                    command.targetStatus(),
                    command.expectedVersion(),
                    context.actor().id(),
                    timeProvider.now());
            dispositionRepository.save(created, command.expectedVersion());
            return InboxDispositionOutcome.of(created);
        }
        InboxDisposition current = committed.orElseThrow();
        if (!current.belongsTo(item)) {
            throw new IllegalStateException(
                    "Persisted Inbox disposition escaped its tenant or member scope");
        }
        // Unmarking an archive is the explicit restore-then-unmark sequence: two matrix steps,
        // two +1 version bumps, one command — the restore is never applied silently.
        if (current.status() == InboxDispositionStatus.ARCHIVED
                && command.targetStatus() == InboxDispositionStatus.UNREAD) {
            UtcTimestamp now = timeProvider.now();
            InboxDisposition restored = current.transitionTo(
                    InboxDispositionStatus.READ, command.expectedVersion(),
                    context.actor().id(), now);
            InboxDisposition unmarked = restored.transitionTo(
                    InboxDispositionStatus.UNREAD, restored.version(),
                    context.actor().id(), now);
            dispositionRepository.save(restored, command.expectedVersion());
            dispositionRepository.save(unmarked, restored.version());
            return InboxDispositionOutcome.of(unmarked);
        }
        InboxDisposition updated = current.transitionTo(
                command.targetStatus(),
                command.expectedVersion(),
                context.actor().id(),
                timeProvider.now());
        if (updated == current) {
            return InboxDispositionOutcome.of(current);
        }
        dispositionRepository.save(updated, command.expectedVersion());
        return InboxDispositionOutcome.of(updated);
    }

    private TeamMember requireActiveMember(
            Principal actor, OrganizationId organizationId, TeamId teamId) {
        Principal requiredActor = Objects.requireNonNull(actor, "actor");
        if (requiredActor.type() != PrincipalType.USER
                || !requiredActor.canAct()
                || !requiredActor.scope().organizationId().equals(organizationId)) {
            throw new PolicyDeniedException(DISPOSE_OWN_INBOX);
        }
        return membershipQuery.findByTeam(organizationId, teamId).stream()
                .filter(member -> member.scope().organizationId().equals(organizationId))
                .filter(member -> member.scope().teamId().equals(teamId))
                .filter(TeamMember::canParticipate)
                .filter(member -> member.userPrincipalId().equals(requiredActor.id()))
                .findFirst()
                .orElseThrow(() -> new PolicyDeniedException(DISPOSE_OWN_INBOX));
    }
}
