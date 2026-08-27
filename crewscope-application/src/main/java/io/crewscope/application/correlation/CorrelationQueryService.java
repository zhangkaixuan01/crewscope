package io.crewscope.application.correlation;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Revalidates active Team membership before every correlation page is read. */
public final class CorrelationQueryService {

    private final WorkItemAccessPolicy accessPolicy;
    private final CorrelationQueryPort queries;
    private final TransactionExecutor transactions;

    public CorrelationQueryService(
            WorkItemAccessPolicy accessPolicy,
            CorrelationQueryPort queries,
            TransactionExecutor transactions) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public CorrelationPage find(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            UUID correlationId,
            Optional<CorrelationCursor> after,
            int limit) {
        return transactions.required(() -> {
            var member = accessPolicy.requireVisibleTeamMember(
                    context, organizationId, teamId);
            CorrelationQuery query = new CorrelationQuery(
                    organizationId, teamId, member.id(), correlationId, after, limit);
            CorrelationPage page = Objects.requireNonNull(
                    queries.find(query), "CorrelationQueryPort.find result");
            if (!page.correlationId().equals(correlationId)) {
                throw new IllegalStateException("Correlation adapter returned another scope");
            }
            return page;
        });
    }

    /** Pre-decode authorization prevents a signed cursor from becoming a membership oracle. */
    public void requireAccess(
            TeamAccessContext context, OrganizationId organizationId, TeamId teamId) {
        transactions.required(() -> {
            accessPolicy.requireVisibleTeamMember(context, organizationId, teamId);
            return null;
        });
    }
}
