package io.crewscope.application.memory;

import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentMemoryEntry;
import io.crewscope.domain.agent.AgentMemoryKey;
import io.crewscope.domain.agent.AgentMemoryOwner;
import io.crewscope.domain.agent.AgentMemoryOwnerKey;
import io.crewscope.domain.agent.AgentMemoryPolicy;
import io.crewscope.domain.agent.AgentMemoryPolicyReference;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Agent assistant memory lifecycle (M10-I02a, S01 §3.6): the member-level view and clear
 * surface plus the runtime write port I02b consumes. Memory is owned by exactly one member
 * on one Agent — the authenticated principal is the owner, so there is no team permission
 * enum and no cross-member read path. The personal on/off switch is the Agent configuration
 * itself (a memory policy reference present = on); the deployment switch
 * {@code crewscope.memory.enabled} only gates new model-facing writes, while view, clear
 * and the TTL sweep keep working. No domain or audit events are emitted by design.
 */
public final class AgentMemoryService {

    private final AgentMemoryRepository memory;
    private final AgentProfileRepository profiles;
    private final AgentConfigurationRepository configurations;
    private final AgentMemoryPolicyCatalog policies;
    private final TeamRepository teams;
    private final TeamMembershipQuery memberships;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;
    private final boolean memoryEnabled;

    public AgentMemoryService(
            AgentMemoryRepository memory,
            AgentProfileRepository profiles,
            AgentConfigurationRepository configurations,
            AgentMemoryPolicyCatalog policies,
            TeamRepository teams,
            TeamMembershipQuery memberships,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            boolean memoryEnabled) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.teams = Objects.requireNonNull(teams, "teams");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.memoryEnabled = memoryEnabled;
    }

    // ------------------------------------------------------------------ member-facing surface

    /**
     * The authenticated member's own memory on one Agent. Viewing is not "using": the TTL is
     * never slid here, and no owner row is created by a read.
     */
    public AgentMemoryView view(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            AgentProfileId profileId) {
        Objects.requireNonNull(context, "context");
        return transactions.required(() -> {
            PrincipalId actor = guardedOwner(context, organizationId, teamId, profileId);
            AgentMemoryOwnerKey key = ownerKey(organizationId, teamId, profileId, actor);
            long generation = memory.findOwner(key)
                    .map(AgentMemoryOwner::clearanceGeneration)
                    .orElse(0L);
            Optional<AgentMemoryPolicyReference> reference =
                    referencedPolicy(organizationId, profileId);
            if (reference.isEmpty()) {
                return new AgentMemoryView(reference, Optional.empty(), generation, List.of());
            }
            Optional<AgentMemoryPolicy> policy = policies.resolve(reference.orElseThrow());
            List<AgentMemoryEntry> entries = policy.isEmpty()
                    ? List.of()
                    : memory.findVisible(key, reference.orElseThrow(), timeProvider.now());
            return new AgentMemoryView(reference, policy, generation, entries);
        });
    }

    /** Clears the member's own memory on one Agent: generation +1, entries of all spaces gone. */
    public AgentMemoryClearance clear(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            AgentProfileId profileId) {
        Objects.requireNonNull(context, "context");
        return transactions.required(() -> {
            PrincipalId actor = guardedOwner(context, organizationId, teamId, profileId);
            AgentMemoryOwnerKey key = ownerKey(organizationId, teamId, profileId, actor);
            return memory.clear(key, actor, timeProvider.now());
        });
    }

    // ------------------------------------------------------------------ runtime write port (I02b)

    /**
     * Writes one frozen preference value into the owner's space. Authorization is the
     * caller's contract (the injecting runtime already holds the execution scope); this port
     * only enforces the memory contract itself — switch, configuration, policy, capacity,
     * value bounds and the clearance generation.
     */
    public AgentMemoryUpsertResult upsert(
            AgentMemoryOwnerKey key, AgentMemoryKey memoryKey, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(memoryKey, "memoryKey");
        Objects.requireNonNull(value, "value");
        try {
            return upsertInTransaction(key, memoryKey, value);
        } catch (OptimisticLockConflictException staleClearance) {
            // The generation gate fires inside the shared transaction, so catching it
            // within the boundary would leave the transaction rollback-only and fail
            // the commit — the catch belongs after the boundary (repo convention, cf.
            // KnowledgeDistillerProvisioningService). Counts re-read the space a retry
            // would see, which the racing clear has just emptied.
            return staleClearance(key);
        }
    }

    private AgentMemoryUpsertResult upsertInTransaction(
            AgentMemoryOwnerKey key, AgentMemoryKey memoryKey, String value) {
        return transactions.required(() -> {
            if (!memoryEnabled) {
                return new AgentMemoryUpsertResult(
                        AgentMemoryUpsertResult.Outcome.MEMORY_DISABLED, Optional.empty(), 0, 0);
            }
            Optional<AgentMemoryPolicyReference> reference =
                    referencedPolicy(key.organizationId(), key.agentProfileId());
            if (reference.isEmpty()) {
                return new AgentMemoryUpsertResult(
                        AgentMemoryUpsertResult.Outcome.NOT_CONFIGURED,
                        Optional.empty(), 0, 0);
            }
            Optional<AgentMemoryPolicy> resolved = policies.resolve(reference.orElseThrow());
            if (resolved.isEmpty()) {
                return new AgentMemoryUpsertResult(
                        AgentMemoryUpsertResult.Outcome.POLICY_UNAVAILABLE,
                        Optional.empty(), 0, 0);
            }
            AgentMemoryPolicy policy = resolved.orElseThrow();
            UtcTimestamp now = timeProvider.now();
            // The capacity count sits behind the owner row lock (ensureOwner takes it
            // FOR UPDATE), so concurrent same-space writers cannot both pass the check.
            AgentMemoryOwner owner = memory.ensureOwner(key, key.ownerPrincipalId(), now);
            List<AgentMemoryEntry> visible =
                    memory.findVisible(key, reference.orElseThrow(), now);
            if (visible.stream().noneMatch(entry -> entry.memoryKey().equals(memoryKey))
                    && visible.size() >= policy.maxEntriesPerOwner()) {
                return new AgentMemoryUpsertResult(
                        AgentMemoryUpsertResult.Outcome.CAPACITY_EXCEEDED,
                        Optional.empty(), visible.size(), policy.maxEntriesPerOwner());
            }
            UtcTimestamp expiresAt = slidingDeadline(policy, now);
            AgentMemoryEntry entry = AgentMemoryEntry.write(
                    key, reference.orElseThrow(), memoryKey, value,
                    owner.clearanceGeneration(), expiresAt,
                    key.ownerPrincipalId(), now);
            return new AgentMemoryUpsertResult(
                    AgentMemoryUpsertResult.Outcome.WRITTEN,
                    Optional.of(memory.upsert(entry)),
                    visible.size() + (visible.stream()
                            .anyMatch(existing -> existing.memoryKey().equals(memoryKey)) ? 0 : 1),
                    policy.maxEntriesPerOwner());
        });
    }

    private AgentMemoryUpsertResult staleClearance(AgentMemoryOwnerKey key) {
        int count = 0;
        int max = 0;
        Optional<AgentMemoryPolicyReference> reference =
                referencedPolicy(key.organizationId(), key.agentProfileId());
        if (reference.isPresent()) {
            Optional<AgentMemoryPolicy> policy = policies.resolve(reference.orElseThrow());
            if (policy.isPresent()) {
                max = policy.orElseThrow().maxEntriesPerOwner();
                count = memory.findVisible(
                        key, reference.orElseThrow(), timeProvider.now()).size();
            }
        }
        return new AgentMemoryUpsertResult(
                AgentMemoryUpsertResult.Outcome.STALE_CLEARANCE,
                Optional.empty(), count, max);
    }

    /**
     * Slides the TTL of one visible entry (use = renewal). Returns false when memory is
     * switched off or the entry is absent, expired or of a stale generation — the caller
     * (I02b injection) skips silently instead of failing the model call.
     */
    public boolean touch(AgentMemoryOwnerKey key, AgentMemoryKey memoryKey) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(memoryKey, "memoryKey");
        return transactions.required(() -> {
            if (!memoryEnabled) {
                return false;
            }
            Optional<AgentMemoryPolicyReference> reference =
                    referencedPolicy(key.organizationId(), key.agentProfileId());
            if (reference.isEmpty()) {
                return false;
            }
            Optional<AgentMemoryPolicy> policy = policies.resolve(reference.orElseThrow());
            if (policy.isEmpty()) {
                return false;
            }
            UtcTimestamp now = timeProvider.now();
            return memory.renew(
                    key, reference.orElseThrow(), memoryKey,
                    slidingDeadline(policy.orElseThrow(), now),
                    key.ownerPrincipalId(), now);
        });
    }

    /**
     * The injection-time read (I02b): visible entries of the configured space, never
     * renewed. Unlike the view surface this port yields zero entries with no signal when
     * the policy reference is configured but unresolvable — the injection path has no
     * degradation code for it (the I02b switch-matrix freeze), so an unresolvable policy
     * means the layer contributes nothing, not that the model call fails.
     */
    public List<AgentMemoryEntry> list(AgentMemoryOwnerKey key) {
        Objects.requireNonNull(key, "key");
        return transactions.required(() ->
                referencedPolicy(key.organizationId(), key.agentProfileId())
                        .map(reference -> memory.findVisible(key, reference, timeProvider.now()))
                        .orElseGet(List::of));
    }

    // ------------------------------------------------------------------ guards (member-level, self only)

    /** Runs the four guard steps and returns the authenticated member as the memory owner. */
    private PrincipalId guardedOwner(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            AgentProfileId profileId) {
        Principal actor = requireOrganizationUser(context, organizationId);
        Team team = requireTeam(organizationId, teamId);
        requireProfile(organizationId, teamId, profileId);
        requireActiveMember(actor, team);
        return actor.id();
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

    private AgentProfile requireProfile(
            OrganizationId organizationId, TeamId teamId, AgentProfileId profileId) {
        AgentProfile profile = profiles.findById(organizationId, profileId)
                .orElseThrow(() -> new AggregateNotFoundException("AgentProfile", profileId));
        if (profile.scope().teamId().filter(teamId::equals).isEmpty()) {
            throw new AggregateNotFoundException("AgentProfile", profileId);
        }
        return profile;
    }

    /** The KnowledgeCommandService guard shape: memory reads sit at member level. */
    private TeamMember requireActiveMember(Principal actor, Team team) {
        return memberships.findByTeam(team.organizationId(), team.id()).stream()
                .filter(member -> member.userPrincipalId().equals(actor.id()))
                .filter(TeamMember::canParticipate)
                .findFirst()
                .orElseThrow(() ->
                        new PolicyDeniedException("access this Agent's assistant memory"));
    }

    // ------------------------------------------------------------------ helpers

    /** The personal on/off switch: memory runs only while the current configuration references a policy. */
    private Optional<AgentMemoryPolicyReference> referencedPolicy(
            OrganizationId organizationId, AgentProfileId profileId) {
        return configurations.findCurrent(organizationId, profileId)
                .flatMap(AgentConfigurationVersion::memoryPolicy);
    }

    private static UtcTimestamp slidingDeadline(AgentMemoryPolicy policy, UtcTimestamp now) {
        return UtcTimestamp.from(now.value().plus(Duration.ofDays(policy.ttlDays())));
    }

    private static AgentMemoryOwnerKey ownerKey(
            OrganizationId organizationId, TeamId teamId,
            AgentProfileId profileId, PrincipalId owner) {
        return new AgentMemoryOwnerKey(organizationId, teamId, profileId, owner);
    }
}
