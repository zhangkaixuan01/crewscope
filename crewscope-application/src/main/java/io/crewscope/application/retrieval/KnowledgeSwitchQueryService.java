package io.crewscope.application.retrieval;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import java.util.Objects;

/**
 * Member-level read of the six deployment knowledge switches (M13b-A03). The board itself is a
 * configuration constant, but the read keeps exactly the guard shape every other knowledge face
 * enforces (the {@code KnowledgeIndexControlService} read guard): an acting organization USER,
 * then a READY, ACTIVE Team, then an ACTIVE member — everything inside one read transaction.
 */
public final class KnowledgeSwitchQueryService {

    private final TeamRepository teams;
    private final TeamMembershipQuery memberships;
    private final TransactionExecutor transactions;
    private final KnowledgeSwitchBoard board;

    public KnowledgeSwitchQueryService(
            TeamRepository teams,
            TeamMembershipQuery memberships,
            TransactionExecutor transactions,
            KnowledgeSwitchBoard board) {
        this.teams = Objects.requireNonNull(teams, "teams");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.board = Objects.requireNonNull(board, "board");
    }

    public KnowledgeSwitchBoard switches(
            TeamAccessContext context, OrganizationId organizationId, TeamId teamId) {
        Principal actor = requireOrganizationUser(context, organizationId);
        return transactions.required(() -> {
            Team team = requireTeam(organizationId, teamId);
            requireActiveMember(actor, team);
            return board;
        });
    }

    private static Principal requireOrganizationUser(
            TeamAccessContext context, OrganizationId organizationId) {
        Principal actor = Objects.requireNonNull(context, "context").actor();
        if (actor.type() != PrincipalType.USER
                || !actor.canAct()
                || !actor.scope().organizationId().equals(organizationId)) {
            throw new PolicyDeniedException("act in this Organization");
        }
        return actor;
    }

    private Team requireTeam(OrganizationId organizationId, TeamId teamId) {
        if (teams.findUninitializedById(organizationId, teamId).isPresent()) {
            throw new DomainValidationException("team.initializationStatus", "must be READY");
        }
        Team team = teams.findById(organizationId, teamId)
                .orElseThrow(() -> new AggregateNotFoundException("Team", teamId));
        if (!team.isActive()) {
            throw new DomainValidationException("team.status", "must be ACTIVE");
        }
        return team;
    }

    private void requireActiveMember(Principal actor, Team team) {
        memberships.findByTeam(team.organizationId(), team.id()).stream()
                .filter(member -> member.userPrincipalId().equals(actor.id()))
                .filter(TeamMember::canParticipate)
                .findFirst()
                .orElseThrow(() -> new PolicyDeniedException("access this Team's knowledge"));
    }
}
