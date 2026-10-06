package io.crewscope.application.observability;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Team-scoped usage-cost and quality reads over the rollup projection and the durable task
 * fact tables (M10-F03). Guards follow the Knowledge read shape — organization USER, Team
 * exists and is ACTIVE, then an active Team member — with the platform administrator bypass
 * applied to the membership check only: administrators still get a 404 for Teams that do
 * not exist in their own Organization.
 *
 * <p>Source subtotals deliberately speak the contract's three-source language: EXECUTION
 * folds the chat roles (CHAT_PRIMARY, CHAT_FALLBACK, COMPACTION) so the cost page can
 * attribute spend by where it came from (embedding, distillation, execution), matching how
 * the budget planner thinks about the bill rather than how the runtime names its roles.
 */
public final class TeamObservabilityQueryService {

    /** The contract's cost-source view over the five persisted usage roles. */
    public enum CostSource {
        EXECUTION,
        EMBEDDING,
        DISTILLATION;

        static CostSource ofRole(String role) {
            return switch (role) {
                case "CHAT_PRIMARY", "CHAT_FALLBACK", "COMPACTION" -> EXECUTION;
                case "EMBEDDING" -> EMBEDDING;
                case "DISTILLATION" -> DISTILLATION;
                default -> throw new IllegalArgumentException("unknown usage role: " + role);
            };
        }
    }

    private static final String UNPRICED_CURRENCY = "XXX";
    private static final int MAX_MONTH_PAGE = 24;

    private final ModelUsageRollupQueryRepository rollups;
    private final TaskQualityStatisticsRepository quality;
    private final TeamRepository teams;
    private final TeamMembershipQuery memberships;
    private final TimeProvider timeProvider;
    private final ZoneId reportingZone;

    public TeamObservabilityQueryService(
            ModelUsageRollupQueryRepository rollups,
            TaskQualityStatisticsRepository quality,
            TeamRepository teams,
            TeamMembershipQuery memberships,
            TimeProvider timeProvider,
            ZoneId reportingZone) {
        this.rollups = Objects.requireNonNull(rollups, "rollups");
        this.quality = Objects.requireNonNull(quality, "quality");
        this.teams = Objects.requireNonNull(teams, "teams");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.reportingZone = Objects.requireNonNull(reportingZone, "reportingZone");
    }

    /**
     * Rejects malformed and future months ({@code IllegalArgumentException}) so transport
     * adapters can map both to one invalid-field response; the current month stays
     * requestable because in-flight months are a legitimate view.
     */
    public String requireRequestableMonth(String usageMonth) {
        String month = ModelUsageMonthKey.formatChecked(usageMonth);
        YearMonth current = YearMonth.from(timeProvider.now().value().atZone(reportingZone));
        if (YearMonth.parse(month).isAfter(current)) {
            throw new IllegalArgumentException("usage month must not be in the future: " + month);
        }
        return month;
    }

    /**
     * Month summary page, newest first. {@code after} is an exclusive month cursor; the
     * page carries {@code limit} months at most and {@code nextAfter} only when a strictly
     * older month exists.
     */
    public TeamCostMonthsPage costMonths(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            Optional<String> after,
            int limit) {
        read(context, organizationId, teamId);
        int pageSize = Math.min(Math.max(limit, 1), MAX_MONTH_PAGE);
        // Read one extra month to detect the next cursor without counting anything twice.
        List<String> window = rollups.findUsageMonths(
                organizationId, teamId, after.orElse(null), pageSize + 1);
        boolean hasMore = window.size() > pageSize;
        List<String> months = hasMore ? window.subList(0, pageSize) : window;
        return new TeamCostMonthsPage(
                summarize(months, rollups.findMonthAggregates(organizationId, teamId, months)),
                hasMore ? Optional.of(months.get(months.size() - 1)) : Optional.empty());
    }

    /** Full-grain detail rows of one month; a month without rollups is an empty list. */
    public List<ModelUsageMonthDetailRow> costMonth(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            String usageMonth) {
        read(context, organizationId, teamId);
        return rollups.findMonthDetail(
                organizationId, teamId, ModelUsageMonthKey.formatChecked(usageMonth));
    }

    /** Quality counters of one month window in the reporting zone. */
    public TaskQualityStatistics qualityMonth(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            String usageMonth) {
        read(context, organizationId, teamId);
        String month = ModelUsageMonthKey.formatChecked(usageMonth);
        LocalDate firstDay = LocalDate.parse(month + "-01");
        Instant windowStart = firstDay.atStartOfDay(reportingZone).toInstant();
        Instant windowEnd = firstDay.plusMonths(1).atStartOfDay(reportingZone).toInstant();
        return quality.findForWindow(organizationId, teamId, windowStart, windowEnd);
    }

    private List<ModelUsageMonthSummary> summarize(
            Collection<String> months, List<ModelUsageMonthRoleCurrencyRow> aggregates) {
        Map<String, Map<CostSource, long[]>> tokensByMonth = new LinkedHashMap<>();
        Map<String, Map<String, BigDecimal[]>> costsByMonth = new LinkedHashMap<>();
        Map<String, long[]> unpricedByMonth = new LinkedHashMap<>();
        for (String month : months) {
            tokensByMonth.put(month, new LinkedHashMap<>());
            costsByMonth.put(month, new LinkedHashMap<>());
            unpricedByMonth.put(month, new long[] {0L, 0L});
        }
        for (ModelUsageMonthRoleCurrencyRow row : aggregates) {
            if (!tokensByMonth.containsKey(row.usageMonth())) {
                continue;
            }
            CostSource source = CostSource.ofRole(row.role());
            long[] tokens = tokensByMonth.get(row.usageMonth())
                    .computeIfAbsent(source, ignored -> new long[4]);
            tokens[0] = Math.addExact(tokens[0], row.inputTokens());
            tokens[1] = Math.addExact(tokens[1], row.outputTokens());
            tokens[2] = Math.addExact(tokens[2], row.cachedInputTokens());
            tokens[3] = Math.addExact(tokens[3], row.factCount());
            if (UNPRICED_CURRENCY.equals(row.currencyCode())) {
                long[] unpriced = unpricedByMonth.get(row.usageMonth());
                unpriced[0] = Math.addExact(unpriced[0], row.totalTokens());
                unpriced[1] = Math.addExact(unpriced[1], row.factCount());
                continue;
            }
            BigDecimal[] costs = costsByMonth.get(row.usageMonth())
                    .computeIfAbsent(row.currencyCode(), ignored -> new BigDecimal[] {
                            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            costs[0] = costs[0].add(nullToZero(row.inputCost()));
            costs[1] = costs[1].add(nullToZero(row.outputCost()));
            costs[2] = costs[2].add(nullToZero(row.cachedInputCost()));
        }
        return months.stream()
                .map(month -> new ModelUsageMonthSummary(
                        month,
                        subtotals(tokensByMonth.get(month)),
                        currencySubtotals(costsByMonth.get(month)),
                        unpricedByMonth.get(month)[0],
                        unpricedByMonth.get(month)[1],
                        tokensByMonth.get(month).values().stream()
                                .mapToLong(tokens -> tokens[3]).sum()))
                .toList();
    }

    private static Map<CostSource, ModelUsageRoleSubtotal> subtotals(
            Map<CostSource, long[]> tokens) {
        Map<CostSource, ModelUsageRoleSubtotal> subtotals = new LinkedHashMap<>();
        for (CostSource source : CostSource.values()) {
            long[] values = tokens.get(source);
            if (values != null) {
                subtotals.put(source, new ModelUsageRoleSubtotal(
                        values[0], values[1], values[2], values[3]));
            }
        }
        return subtotals;
    }

    private static Map<String, ModelUsageCurrencySubtotal> currencySubtotals(
            Map<String, BigDecimal[]> costs) {
        Map<String, ModelUsageCurrencySubtotal> subtotals = new LinkedHashMap<>();
        costs.forEach((currency, amounts) -> subtotals.put(
                currency,
                new ModelUsageCurrencySubtotal(amounts[0], amounts[1], amounts[2])));
        return subtotals;
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    // ------------------------------------------------------------------ guards

    private void read(TeamAccessContext context, OrganizationId organizationId, TeamId teamId) {
        Principal actor = requireOrganizationUser(context, organizationId);
        Team team = requireTeam(organizationId, teamId);
        if (context.platformAdministrator()) {
            return;
        }
        requireActiveMember(actor, team);
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
        Team team = teams.findById(organizationId, teamId)
                .orElseThrow(() -> new AggregateNotFoundException("Team", teamId));
        if (!team.isActive()) {
            throw new AggregateNotFoundException("Team", teamId);
        }
        return team;
    }

    private void requireActiveMember(Principal actor, Team team) {
        memberships.findByTeam(team.organizationId(), team.id()).stream()
                .filter(member -> member.userPrincipalId().equals(actor.id()))
                .filter(TeamMember::canParticipate)
                .findFirst()
                .orElseThrow(() -> new PolicyDeniedException("access this Team's observability"));
    }

    // ------------------------------------------------------------------ results

    /** One page of month summaries plus the exclusive cursor when older months remain. */
    public record TeamCostMonthsPage(
            List<ModelUsageMonthSummary> months, Optional<String> nextAfter) {
    }

    public record ModelUsageMonthSummary(
            String usageMonth,
            Map<CostSource, ModelUsageRoleSubtotal> roles,
            Map<String, ModelUsageCurrencySubtotal> currencies,
            long unpricedTokens,
            long unpricedFactCount,
            long totalFactCount) {
    }

    public record ModelUsageRoleSubtotal(
            long inputTokens, long outputTokens, long cachedTokens, long factCount) {
    }

    public record ModelUsageCurrencySubtotal(
            BigDecimal inputCost, BigDecimal outputCost, BigDecimal cachedInputCost) {
    }
}
