package io.crewscope.application.principal;

import io.crewscope.application.audit.AuditAuthorization;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.team.TeamMember;
import java.util.Objects;

/**
 * Full-set Team subject directory read (M9b-A06).
 *
 * <p>Membership still gates every purpose, and an AUDIT purpose re-evaluates the Team's audit
 * permission on every request. Everything else — the authorized candidate set, the stable sort,
 * the filters and the keyset/offset paging — is resolved by the repository in SQL, so no Team is
 * truncated at an in-memory window and roles are joined per page instead of per member.
 */
public final class PrincipalDirectoryQueryService {

    private final PrincipalDirectoryAccessPolicy accessPolicy;
    private final AuditAuthorization auditAuthorization;
    private final PrincipalDirectoryRepository directory;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public PrincipalDirectoryQueryService(
            PrincipalDirectoryAccessPolicy accessPolicy,
            AuditAuthorization auditAuthorization,
            PrincipalDirectoryRepository directory,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.auditAuthorization = Objects.requireNonNull(auditAuthorization, "auditAuthorization");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public PrincipalDirectoryPage search(
            TeamAccessContext context, PrincipalDirectoryQuery query) {
        PrincipalDirectoryQuery required = Objects.requireNonNull(query, "query");
        return transactions.required(() -> {
            TeamMember viewer = accessPolicy.requireMember(
                    context, required.organizationId(), required.teamId());
            if (required.purpose() == PrincipalDirectoryPurpose.AUDIT) {
                auditAuthorization.requireRead(
                        context,
                        required.organizationId(),
                        required.teamId(),
                        timeProvider.now());
            }
            return directory.search(required, viewer.id());
        });
    }
}
