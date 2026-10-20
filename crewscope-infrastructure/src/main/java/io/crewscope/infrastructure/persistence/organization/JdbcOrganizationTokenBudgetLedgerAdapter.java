package io.crewscope.infrastructure.persistence.organization;

import io.crewscope.application.quota.OrganizationTokenBudgetLedger;
import io.crewscope.application.quota.QuotaLedgerContextException;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pre-effect token reservation ledger on the V70 {@code crewscope.organization_quota_token_ledger}
 * (workspace write budget form). Each reporting month materializes lazily as a zero row; a
 * reservation is the task policy's token upper bound and is never refunded (commitment
 * semantics, ADR-037 §3 as narrowed for M12-A02).
 */
@Repository
public class JdbcOrganizationTokenBudgetLedgerAdapter implements OrganizationTokenBudgetLedger {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcOrganizationTokenBudgetLedgerAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Decision reserve(ReservationRequest request) {
        ReservationRequest required = Objects.requireNonNull(request, "request");
        MapSqlParameterSource parameters = baseParameters(required);
        jdbc.update(
                """
                INSERT INTO crewscope.organization_quota_token_ledger (
                    organization_id, usage_month, reserved_tokens, reservation_sequence,
                    version, created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (
                    :organizationId, :usageMonth, 0, 0, 0,
                    CURRENT_TIMESTAMP, :actor, CURRENT_TIMESTAMP, :actor
                ) ON CONFLICT (organization_id, usage_month) DO NOTHING
                """,
                parameters);
        LedgerRow current = lock(parameters);
        try {
            long newReserved = Math.addExact(current.reservedTokens(), required.reservationTokens());
            long newSequence = Math.addExact(current.reservationSequence(), 1L);
            if (newReserved > required.budgetLimit()) {
                return Decision.BUDGET_EXCEEDED;
            }
            int updated = jdbc.update(
                    """
                    UPDATE crewscope.organization_quota_token_ledger
                       SET reserved_tokens = :newReserved,
                           reservation_sequence = :newSequence,
                           version = version + 1,
                           updated_at = GREATEST(CURRENT_TIMESTAMP, updated_at),
                           updated_by_principal_id = :actor
                     WHERE organization_id = :organizationId
                       AND usage_month = :usageMonth
                       AND reservation_sequence = :currentSequence
                    """,
                    parameters
                            .addValue("newReserved", newReserved)
                            .addValue("newSequence", newSequence)
                            .addValue("currentSequence", current.reservationSequence()));
            if (updated != 1) {
                throw new QuotaLedgerContextException(
                        "Organization token budget reservation lost its current context");
            }
            return Decision.RESERVED;
        } catch (ArithmeticException overflow) {
            return Decision.BUDGET_EXCEEDED;
        }
    }

    private LedgerRow lock(MapSqlParameterSource parameters) {
        List<LedgerRow> rows = jdbc.query(
                """
                SELECT reserved_tokens, reservation_sequence
                  FROM crewscope.organization_quota_token_ledger
                 WHERE organization_id = :organizationId AND usage_month = :usageMonth
                 FOR UPDATE
                """,
                parameters,
                (row, ignored) -> new LedgerRow(
                        row.getLong("reserved_tokens"), row.getLong("reservation_sequence")));
        if (rows.size() != 1) {
            throw new QuotaLedgerContextException(
                    "Organization token budget month row vanished after upsert");
        }
        return rows.get(0);
    }

    private static MapSqlParameterSource baseParameters(ReservationRequest request) {
        return new MapSqlParameterSource()
                .addValue("organizationId", request.organizationId().value())
                .addValue("usageMonth", request.usageMonth())
                .addValue("actor", request.actor().value());
    }

    private record LedgerRow(long reservedTokens, long reservationSequence) {
    }
}
