package io.crewscope.application.retrieval;

import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.retrieval.SourceCommit;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.MemberRoleStatus;
import io.crewscope.domain.team.RoleScope;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Control plane of the knowledge index (M10-I01c): the HTTP-facing rebuild,
 * repository-build, cancel and job-query operations over the durable job model
 * delivered by I01b. Reads require an active Team membership; every command
 * additionally requires {@link TeamPermission#KNOWLEDGE_MANAGE} (platform
 * administrators bypass) — the same guard shape as the knowledge command and
 * distillation services. Repository builds are validated against the
 * four-coordinate RepositoryBinding lookup before enqueue, so one Team can never
 * index another Team's mirror into its own vector space; commit readability is
 * deliberately not pre-validated here (the control plane never touches git) and
 * surfaces later as the job's stable failure code.
 */
public final class KnowledgeIndexControlService {

    private final KnowledgeIndexJobService enqueue;
    private final KnowledgeIndexJobRepository jobs;
    private final RepositoryBindingRepository bindings;
    private final TeamRepository teams;
    private final TeamMembershipQuery memberships;
    private final TeamRoleRepository roles;
    private final MemberRoleRepository grants;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public KnowledgeIndexControlService(
            KnowledgeIndexJobService enqueue,
            KnowledgeIndexJobRepository jobs,
            RepositoryBindingRepository bindings,
            TeamRepository teams,
            TeamMembershipQuery memberships,
            TeamRoleRepository roles,
            MemberRoleRepository grants,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.enqueue = Objects.requireNonNull(enqueue, "enqueue");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.teams = Objects.requireNonNull(teams, "teams");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /**
     * Seeds a refresh job for every effective version of the Team without a live job;
     * returns jobs created. Zero means nothing to do or the refresh gate is closed.
     */
    public int rebuild(TeamAccessContext context, OrganizationId organizationId, TeamId teamId) {
        return command(context, organizationId, teamId, (TeamMember member) ->
                transactions.required(() -> enqueue.rebuildTeamKnowledge(
                        organizationId, teamId, member.userPrincipalId())));
    }

    /**
     * Enqueues one repository index build. The binding must exist under the caller's
     * four coordinates and accept new targets; {@code accepted=false} means the
     * refresh gate is closed — a skip, not an error.
     */
    public RepositoryBuildEnqueueResult enqueueRepositoryBuild(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            RepositoryBindingId bindingId,
            SourceCommit commit) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(bindingId, "bindingId");
        Objects.requireNonNull(commit, "commit");
        return command(context, organizationId, teamId, (TeamMember member) -> {
            RepositoryBinding binding = bindings
                    .findById(organizationId, teamId, projectId, bindingId)
                    .orElseThrow(() -> new AggregateNotFoundException(
                            "RepositoryBinding", bindingId));
            if (!binding.acceptsNewTargets()) {
                throw new DomainValidationException(
                        "repositoryIndex.bindingId",
                        "must reference an active RepositoryBinding of this Team's WorkProject");
            }
            return transactions.required(() -> enqueue.enqueueRepositoryBuild(
                            organizationId, teamId, projectId, bindingId, commit,
                            member.userPrincipalId()))
                    .map(RepositoryBuildEnqueueResult::accepted)
                    .orElseGet(RepositoryBuildEnqueueResult::skipped);
        });
    }

    /**
     * Cancels a still-QUEUED job and returns its snapshot. Idempotent for an
     * already-CANCELLED job; not-cancellable (claimed, READY or FAILED) throws
     * {@link KnowledgeIndexJobNotCancellableException}; unknown or cross-tenant ids
     * throw {@link KnowledgeIndexJobNotFoundException}. Cancelling is a management
     * operation and is never gated by the refresh switch.
     */
    public KnowledgeIndexJob cancel(
            TeamAccessContext context, OrganizationId organizationId, TeamId teamId, UUID jobId) {
        Objects.requireNonNull(jobId, "jobId");
        return command(context, organizationId, teamId, (TeamMember member) ->
                transactions.required(() -> cancelClaimedAware(
                        requireJob(organizationId, teamId, jobId), timeProvider.now())));
    }

    /** One job's snapshot; unknown and cross-tenant ids share the same 404 shape. */
    public KnowledgeIndexJob job(
            TeamAccessContext context, OrganizationId organizationId, TeamId teamId, UUID jobId) {
        Objects.requireNonNull(jobId, "jobId");
        return read(context, organizationId, teamId, member -> transactions.required(() ->
                requireJob(organizationId, teamId, jobId)));
    }

    /**
     * Lists the Team's jobs. The keyset cursor must resolve to a job of the same
     * Team, otherwise {@link IllegalArgumentException} — callers translate that into
     * their request-level invalid-cursor error.
     */
    public KnowledgeIndexJobPage list(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            KnowledgeIndexJobFilter filter,
            KnowledgeIndexJobPageRequest pageRequest) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(pageRequest, "pageRequest");
        return read(context, organizationId, teamId, member -> {
            pageRequest.afterJobId().ifPresent(after -> {
                if (jobs.findById(organizationId, teamId, after).isEmpty()) {
                    throw new IllegalArgumentException(
                            "after must reference a job of the same Team: " + after);
                }
            });
            return transactions.required(() ->
                    jobs.findByTeam(organizationId, teamId, filter, pageRequest));
        });
    }

    // ------------------------------------------------------------------ cancel flow

    private KnowledgeIndexJob cancelClaimedAware(KnowledgeIndexJob job, UtcTimestamp now) {
        if (job.status() == KnowledgeIndexJobStatus.CANCELLED) {
            return job;
        }
        if (job.status() == KnowledgeIndexJobStatus.QUEUED) {
            return jobs.cancelQueued(job, now)
                    .orElseGet(() -> {
                        // The claim CTE won the race; re-read once and judge finally.
                        KnowledgeIndexJob latest = requireJob(
                                job.organizationId(), job.teamId(), job.id());
                        if (latest.status() == KnowledgeIndexJobStatus.CANCELLED) {
                            return latest;
                        }
                        throw new KnowledgeIndexJobNotCancellableException(
                                job.id(), latest.status());
                    });
        }
        throw new KnowledgeIndexJobNotCancellableException(job.id(), job.status());
    }

    private KnowledgeIndexJob requireJob(
            OrganizationId organizationId, TeamId teamId, UUID jobId) {
        return jobs.findById(organizationId, teamId, jobId)
                .orElseThrow(() -> new KnowledgeIndexJobNotFoundException(jobId));
    }

    // ------------------------------------------------------------------ guards

    /** The KnowledgeCommandService guard shape: reads at member level. */
    private <T> T read(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            Function<TeamMember, T> action) {
        Principal actor = requireOrganizationUser(context, organizationId);
        Team team = requireTeam(organizationId, teamId);
        TeamMember member = requireActiveMember(actor, team);
        return action.apply(member);
    }

    /** Commands add KNOWLEDGE_MANAGE on top of the read guard. */
    private <T> T command(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            Function<TeamMember, T> action) {
        Principal actor = requireOrganizationUser(context, organizationId);
        Team team = requireTeam(organizationId, teamId);
        TeamMember member = requireActiveMember(actor, team);
        requireKnowledgeManage(context, team, member, timeProvider.now());
        return transactions.required(() -> action.apply(member));
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

    private TeamMember requireActiveMember(Principal actor, Team team) {
        return memberships.findByTeam(team.organizationId(), team.id()).stream()
                .filter(member -> member.userPrincipalId().equals(actor.id()))
                .filter(TeamMember::canParticipate)
                .findFirst()
                .orElseThrow(() -> new PolicyDeniedException("access this Team's knowledge"));
    }

    private void requireKnowledgeManage(
            TeamAccessContext context, Team team, TeamMember member, UtcTimestamp now) {
        if (context.platformAdministrator()) {
            return;
        }
        Map<TeamRoleId, TeamRole> rolesById = roles
                .findByTeam(team.organizationId(), team.id()).stream()
                .collect(Collectors.toMap(TeamRole::id, Function.identity()));
        boolean allowed = grants.findByMember(team.organizationId(), member.id()).stream()
                .filter(grant -> grant.status() == MemberRoleStatus.ACTIVE)
                .filter(grant -> grant.isEffectiveAt(now))
                .filter(grant -> grant.roleScope().equals(RoleScope.team()))
                .map(grant -> rolesById.get(grant.teamRoleId()))
                .filter(Objects::nonNull)
                .filter(TeamRole::isGrantable)
                .anyMatch(role -> role.permissions().contains(TeamPermission.KNOWLEDGE_MANAGE));
        if (!allowed) {
            throw new PolicyDeniedException("manage this Team's knowledge");
        }
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
}
