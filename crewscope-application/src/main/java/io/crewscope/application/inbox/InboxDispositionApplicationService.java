package io.crewscope.application.inbox;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.inbox.InboxDisposition;
import io.crewscope.domain.inbox.InboxItem;
import io.crewscope.domain.inbox.InboxItemId;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
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

    public InboxDisposition change(
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

    private InboxDisposition changeInTransaction(
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
        InboxDisposition updated;
        if (committed.isEmpty()) {
            updated = InboxDisposition.create(
                    item,
                    command.targetStatus(),
                    command.expectedVersion(),
                    context.actor().id(),
                    timeProvider.now());
        } else {
            InboxDisposition current = committed.orElseThrow();
            if (!current.belongsTo(item)) {
                throw new IllegalStateException(
                        "Persisted Inbox disposition escaped its tenant or member scope");
            }
            updated = current.transitionTo(
                    command.targetStatus(),
                    command.expectedVersion(),
                    context.actor().id(),
                    timeProvider.now());
            if (updated == current) {
                return current;
            }
        }
        dispositionRepository.save(updated, command.expectedVersion());
        return updated;
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
