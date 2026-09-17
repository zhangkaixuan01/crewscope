package io.crewscope.application.setup;

import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberStatus;
import io.crewscope.domain.workspace.AgentProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Searches configuration metadata without exposing prompt, secret or provider values. */
public final class ConfigurationSearchApplicationService {
    private static final int MAX_PROFILES = 200;
    private static final int MAX_RESULTS = 100;

    private final WorkItemAccessPolicy accessPolicy;
    private final TeamMembershipQuery memberships;
    private final AgentProfileRepository profiles;
    private final AgentConfigurationRepository configurations;
    private final io.crewscope.application.transaction.TransactionExecutor transactions;

    public ConfigurationSearchApplicationService(
            WorkItemAccessPolicy accessPolicy,
            TeamMembershipQuery memberships,
            AgentProfileRepository profiles,
            AgentConfigurationRepository configurations,
            io.crewscope.application.transaction.TransactionExecutor transactions) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public List<ConfigurationSearchResult> search(
            TeamAccessContext context, OrganizationId organizationId, TeamId teamId, String query) {
        String term = requireQuery(query);
        return transactions.required(() -> {
            accessPolicy.requireVisibleTeam(context, organizationId, teamId);
            TeamMember member = memberships.findByTeam(organizationId, teamId).stream()
                    .filter(value -> value.status() == TeamMemberStatus.ACTIVE)
                    .filter(value -> value.userPrincipalId().equals(context.actor().id()))
                    .findFirst()
                    .orElseThrow(() -> new io.crewscope.domain.shared.error.PolicyDeniedException(
                            "search Team configuration"));
            List<AgentProfile> visible = profiles.findVisibleToMember(
                    organizationId, teamId, member.id(), 0, MAX_PROFILES);
            List<ConfigurationSearchResult> results = new ArrayList<>();
            for (AgentProfile profile : visible) {
                AgentConfigurationVersion configuration = configurations.findCurrent(
                        organizationId, profile.id()).orElse(null);
                if (configuration == null) continue;
                addIfMatches(results, term, profile, configuration, "modelBinding", "模型绑定");
                addIfMatches(results, term, profile, configuration, "supplementalInstructions", "补充指令");
                addIfMatches(results, term, profile, configuration, "approvedSkills", "已批准技能");
                addIfMatches(results, term, profile, configuration, "memoryPolicy", "记忆策略");
                addIfMatches(results, term, profile, configuration, "budgetPolicy", "预算策略");
                addIfMatches(results, term, profile, configuration, "generateOptions", "生成参数");
                addIfMatches(results, term, profile, configuration, "policyPack", "策略包");
                if (results.size() >= MAX_RESULTS) return List.copyOf(results.subList(0, MAX_RESULTS));
            }
            return List.copyOf(results);
        });
    }

    private static void addIfMatches(
            List<ConfigurationSearchResult> results, String term, AgentProfile profile,
            AgentConfigurationVersion configuration, String field, String label) {
        if (field.toLowerCase(Locale.ROOT).contains(term)
                || label.toLowerCase(Locale.ROOT).contains(term)
                || configuration.templateVersion().key().value().toLowerCase(Locale.ROOT).contains(term)) {
            results.add(new ConfigurationSearchResult(profile.id().toString(),
                    configuration.revision().value(), field, label,
                    "/settings/agents?agent=" + profile.id()));
        }
    }

    /*
     * The HTTP boundary validates the same range and reports `invalid_request`; this guard is the
     * application-side precondition. It throws a domain error rather than a bare
     * `IllegalArgumentException`, which the API error handler has no mapping for and would surface
     * as an internal error.
     */
    private static String requireQuery(String query) {
        if (query == null || query.strip().length() < 1 || query.strip().length() > 100) {
            throw new io.crewscope.domain.shared.error.DomainValidationException(
                    "q", "must contain between 1 and 100 characters");
        }
        return query.strip().toLowerCase(Locale.ROOT);
    }
}
