package io.crewscope.application.principal;

import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.team.MemberRoleStatus;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberStatus;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.workspace.AgentProfileStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Provides a bounded USER + AGENT directory from existing identity and membership facts. */
public final class PrincipalDirectoryQueryService {
    private final PrincipalDirectoryAccessPolicy accessPolicy;
    private final TeamMembershipQuery memberships;
    private final PrincipalRepository principals;
    private final AgentProfileRepository profiles;
    private final MemberRoleRepository memberRoles;
    private final TeamRoleRepository teamRoles;
    private final io.crewscope.application.transaction.TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public PrincipalDirectoryQueryService(
            PrincipalDirectoryAccessPolicy accessPolicy,
            TeamMembershipQuery memberships,
            PrincipalRepository principals,
            AgentProfileRepository profiles,
            MemberRoleRepository memberRoles,
            TeamRoleRepository teamRoles,
            io.crewscope.application.transaction.TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.memberRoles = Objects.requireNonNull(memberRoles, "memberRoles");
        this.teamRoles = Objects.requireNonNull(teamRoles, "teamRoles");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public PrincipalDirectoryPage search(
            TeamAccessContext context, PrincipalDirectoryQuery query) {
        PrincipalDirectoryQuery required = Objects.requireNonNull(query, "query");
        return transactions.required(() -> {
            TeamMember current = accessPolicy.requireMember(
                    context, required.organizationId(), required.teamId());
            var now = timeProvider.now();
            List<TeamMember> members = memberships.findByTeam(
                    required.organizationId(), required.teamId()).stream()
                    .filter(value -> value.status() == TeamMemberStatus.ACTIVE)
                    .toList();
            Set<PrincipalId> ids = new HashSet<>();
            members.forEach(value -> ids.add(value.userPrincipalId()));
            List<io.crewscope.domain.workspace.AgentProfile> visibleProfiles = profiles.findVisibleToMember(
                            required.organizationId(), required.teamId(), current.id(), 0, 200);
            visibleProfiles
                    .stream().filter(value -> value.status() == AgentProfileStatus.ACTIVE)
                    .map(value -> value.agentPrincipalId()).forEach(ids::add);
            Map<PrincipalId, Principal> directory = principals.findByIds(
                            required.organizationId(), ids).stream()
                    .collect(Collectors.toMap(Principal::id, Function.identity()));
            Map<TeamRoleId, TeamRole> roleDefinitions = teamRoles
                    .findByTeam(required.organizationId(), required.teamId()).stream()
                    .collect(Collectors.toMap(TeamRole::id, Function.identity()));
            List<PrincipalDirectoryEntry> entries = new ArrayList<>();
            members.forEach(member -> addUser(entries, member, directory, roleDefinitions, now));
            visibleProfiles.stream()
                    .filter(value -> value.status() == AgentProfileStatus.ACTIVE)
                    .forEach(profile -> addAgent(entries, profile.agentPrincipalId(), directory));
            String prefix = required.namePrefix().map(value -> value.toLowerCase(Locale.ROOT)).orElse("");
            List<PrincipalDirectoryEntry> filtered = entries.stream()
                    .filter(value -> prefix.isEmpty()
                            || value.displayName().toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(Comparator.comparing(PrincipalDirectoryEntry::displayName)
                            .thenComparing(value -> value.principalId().toString()))
                    .toList();
            int from = Math.min(required.offset(), filtered.size());
            int to = Math.min(from + required.limit(), filtered.size());
            OptionalInt next = to < filtered.size() ? OptionalInt.of(to) : OptionalInt.empty();
            return new PrincipalDirectoryPage(filtered.subList(from, to), next);
        });
    }

    private void addUser(List<PrincipalDirectoryEntry> entries, TeamMember member,
            Map<PrincipalId, Principal> directory, Map<TeamRoleId, TeamRole> roles,
            io.crewscope.domain.shared.time.UtcTimestamp now) {
        Principal principal = directory.get(member.userPrincipalId());
        if (principal == null) return;
        List<String> roleKeys = memberRoles.findByMember(
                        member.scope().organizationId(), member.id()).stream()
                .filter(value -> value.status() == MemberRoleStatus.ACTIVE)
                .filter(value -> value.isEffectiveAt(now))
                .map(value -> roles.get(value.teamRoleId()))
                .filter(Objects::nonNull).filter(TeamRole::isGrantable)
                .map(value -> value.key().value()).distinct().sorted().toList();
        entries.add(new PrincipalDirectoryEntry(principal.id(), PrincipalKind.USER,
                principal.displayName(), principal.status(), roleKeys));
    }

    private void addAgent(List<PrincipalDirectoryEntry> entries, PrincipalId id,
            Map<PrincipalId, Principal> directory) {
        Principal principal = directory.get(id);
        if (principal != null) {
            entries.add(new PrincipalDirectoryEntry(principal.id(), PrincipalKind.AGENT,
                    principal.displayName(), principal.status(), List.of()));
        }
    }
}
