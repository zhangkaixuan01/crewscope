package io.crewscope.application.quota;

import io.crewscope.domain.shared.id.OrganizationId;
import java.time.Instant;
import java.util.Optional;

/**
 * Read-side facts backing the quota usage view (M12-A02): the authoritative intake count, the
 * month's reservation total and the organization's actual usage folded from the rollup. The
 * actual grain is organization+month — per-task actuals do not exist (no task dimension on
 * ModelUsageFactRecorded), which is exactly why the budget dimension reserves upper bounds.
 */
public interface OrganizationQuotaUsageQueryRepository {

    long countTasksCreatedIn(
            OrganizationId organizationId, Instant startInclusive, Instant endExclusive);

    Optional<TokenLedgerSnapshot> findTokenLedger(OrganizationId organizationId, String usageMonth);

    ActualTokenUsage sumActualTokens(OrganizationId organizationId, String usageMonth);

    record TokenLedgerSnapshot(long reservedTokens) {

        public TokenLedgerSnapshot {
            if (reservedTokens < 0) {
                throw new IllegalArgumentException("reservedTokens must not be negative");
            }
        }
    }

    record ActualTokenUsage(
            long inputTokens, long outputTokens, long cachedInputTokens, long totalTokens) {

        public ActualTokenUsage {
            if (inputTokens < 0 || outputTokens < 0 || cachedInputTokens < 0 || totalTokens < 0) {
                throw new IllegalArgumentException("token totals must not be negative");
            }
            if (totalTokens != inputTokens + outputTokens + cachedInputTokens) {
                throw new IllegalArgumentException("totalTokens must fold its three parts");
            }
        }
    }
}
