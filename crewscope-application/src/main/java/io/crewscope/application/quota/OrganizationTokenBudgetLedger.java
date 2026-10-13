package io.crewscope.application.quota;

import io.crewscope.application.observability.ModelUsageMonthKey;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;

/**
 * Pre-effect token reservation ledger (workspace write budget form, V70). Commitment
 * semantics: a reservation books the task's policy budget upper bound at admission and is
 * never refunded at terminal state; each reporting month starts from a fresh zero row.
 */
public interface OrganizationTokenBudgetLedger {

    /**
     * Reserves {@code reservationTokens} for the month in the caller's transaction; a declined
     * reservation leaves the ledger untouched.
     */
    Decision reserve(ReservationRequest request);

    enum Decision {
        RESERVED,
        BUDGET_EXCEEDED
    }

    record ReservationRequest(
            OrganizationId organizationId,
            String usageMonth,
            long reservationTokens,
            long budgetLimit,
            PrincipalId actor) {

        public ReservationRequest {
            Objects.requireNonNull(organizationId, "organizationId");
            Objects.requireNonNull(actor, "actor");
            ModelUsageMonthKey.formatChecked(usageMonth);
            if (reservationTokens < 0) {
                throw new IllegalArgumentException("reservation tokens must not be negative");
            }
            if (budgetLimit < 1) {
                throw new IllegalArgumentException("the token budget limit must be positive");
            }
        }
    }
}
