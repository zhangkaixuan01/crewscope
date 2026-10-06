package io.crewscope.application.observability;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Read port behind the budget scan (M10-F03): the active-team sweep and the current
 * month's usage per team, sourced from the same rollup projection the observability
 * reads serve. Unpriced 'XXX' rows count towards tokens but never towards an amount.
 */
public interface TeamBudgetQueryRepository {

    /** Every ACTIVE team across organizations — one scan pass covers the whole platform. */
    List<TeamBudgetTarget> findActiveTeams();

    /** The team's rolled-up usage for one usage month; empty when no facts exist yet. */
    Optional<TeamMonthlyUsage> findMonthlyUsage(
            OrganizationId organizationId, TeamId teamId, String usageMonth);

    record TeamBudgetTarget(OrganizationId organizationId, TeamId teamId, String teamName) {

        public TeamBudgetTarget {
            Objects.requireNonNull(organizationId, "organizationId");
            Objects.requireNonNull(teamId, "teamId");
            if (teamName == null || teamName.isBlank()) {
                throw new IllegalArgumentException("teamName must not be blank");
            }
        }
    }

    /** Costs are listed per currency — never converted or summed across currencies. */
    record TeamMonthlyUsage(long totalTokens, Map<String, BigDecimal> costsByCurrency) {

        public TeamMonthlyUsage {
            if (totalTokens < 0) {
                throw new IllegalArgumentException("totalTokens must not be negative");
            }
            costsByCurrency = Map.copyOf(Objects.requireNonNull(costsByCurrency, "costs"));
        }

        public static TeamMonthlyUsage empty() {
            return new TeamMonthlyUsage(0L, Map.of());
        }
    }
}
