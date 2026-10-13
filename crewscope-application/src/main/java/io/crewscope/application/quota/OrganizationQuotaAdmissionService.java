package io.crewscope.application.quota;

import io.crewscope.domain.organization.quota.OrganizationQuotaConfiguration;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Admits a task creation against the organization's quota configuration, inside the caller's
 * required transaction (M12-A02). Unconfigured organizations take the zero-cost path — no
 * lock, no ledger touch; a declined admission surfaces as {@link PolicyDeniedException} (403)
 * while storage failures propagate unchecked so the transaction fails closed (5xx).
 */
public final class OrganizationQuotaAdmissionService {

    static final String INTAKE_LIMIT_ACTION =
            "create more agent tasks this month (organization monthly task intake limit reached)";
    static final String TOKEN_BUDGET_ACTION =
            "create an agent task reserving additional model tokens this month "
            + "(organization monthly token budget reached)";

    private final OrganizationQuotaConfigurationRepository configurations;
    private final OrganizationTaskIntakeQuotaCounter intakeCounter;
    private final OrganizationTokenBudgetLedger tokenLedger;
    private final ZoneId reportingZone;

    public OrganizationQuotaAdmissionService(
            OrganizationQuotaConfigurationRepository configurations,
            OrganizationTaskIntakeQuotaCounter intakeCounter,
            OrganizationTokenBudgetLedger tokenLedger,
            ZoneId reportingZone) {
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.intakeCounter = Objects.requireNonNull(intakeCounter, "intakeCounter");
        this.tokenLedger = Objects.requireNonNull(tokenLedger, "tokenLedger");
        this.reportingZone = Objects.requireNonNull(reportingZone, "reportingZone");
    }

    /**
     * Must run in the task-creation transaction, after the creation policy resolves and before
     * the task row inserts. Returns quietly on admission.
     */
    public void requireAdmission(OrganizationQuotaAdmissionRequest request) {
        Objects.requireNonNull(request, "request");
        OrganizationQuotaConfiguration configuration =
                configurations.findByOrganization(request.organizationId()).orElse(null);
        if (configuration == null
                || (configuration.monthlyTaskIntakeLimit().isEmpty()
                    && configuration.monthlyTokenBudget().isEmpty())) {
            return;
        }
        QuotaMonthCalendar.MonthWindow monthWindow =
                QuotaMonthCalendar.monthOf(request.occurredAt(), reportingZone);
        if (configuration.monthlyTaskIntakeLimit().isPresent()) {
            OrganizationTaskIntakeQuotaCounter.Decision intake =
                    intakeCounter.check(new OrganizationTaskIntakeQuotaCounter.QuotaQuery(
                            request.organizationId(),
                            monthWindow.startInclusive(),
                            monthWindow.endExclusive(),
                            configuration.monthlyTaskIntakeLimit().orElseThrow()));
            if (intake == OrganizationTaskIntakeQuotaCounter.Decision.INTAKE_LIMIT_REACHED) {
                throw new PolicyDeniedException(INTAKE_LIMIT_ACTION);
            }
        }
        if (configuration.monthlyTokenBudget().isPresent() && request.taskMaxTokens() > 0) {
            OrganizationTokenBudgetLedger.Decision reservation =
                    tokenLedger.reserve(new OrganizationTokenBudgetLedger.ReservationRequest(
                            request.organizationId(),
                            monthWindow.usageMonth(),
                            request.taskMaxTokens(),
                            configuration.monthlyTokenBudget().orElseThrow(),
                            request.actor()));
            if (reservation == OrganizationTokenBudgetLedger.Decision.BUDGET_EXCEEDED) {
                throw new PolicyDeniedException(TOKEN_BUDGET_ACTION);
            }
        }
    }

    /**
     * @param taskMaxTokens the creation policy's token upper bound for the task; zero means the
     *     policy budgets nothing and the ledger is skipped
     */
    public record OrganizationQuotaAdmissionRequest(
            OrganizationId organizationId,
            PrincipalId actor,
            long taskMaxTokens,
            UtcTimestamp occurredAt) {

        public OrganizationQuotaAdmissionRequest {
            Objects.requireNonNull(organizationId, "organizationId");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(occurredAt, "occurredAt");
            if (taskMaxTokens < 0) {
                throw new IllegalArgumentException("taskMaxTokens must not be negative");
            }
        }
    }
}
