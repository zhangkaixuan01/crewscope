package io.crewscope.application.organization;

import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.organization.role.OrganizationCapability;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;

/**
 * Full-set organization member directory read (M13b-A01) — the picker source for governance
 * forms and the member-management page.
 *
 * <p>Every request re-evaluates the {@code org:audit-read} capability from the caller's ACTIVE
 * ORG_* grants, so an AUDITOR keeps the read while a plain member never sees the roster. The
 * candidate set, the stable sort and both paging modes are resolved by the repository in SQL.
 */
public final class OrganizationMemberDirectoryQueryService {

    private final OrganizationAuthorization authorization;
    private final OrganizationMemberDirectoryRepository directory;
    private final TransactionExecutor transactions;

    public OrganizationMemberDirectoryQueryService(
            OrganizationAuthorization authorization,
            OrganizationMemberDirectoryRepository directory,
            TransactionExecutor transactions) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public OrganizationMemberDirectoryPage search(
            OrganizationAccessContext access, OrganizationMemberDirectoryQuery query) {
        OrganizationMemberDirectoryQuery required = Objects.requireNonNull(query, "query");
        OrganizationId organizationId = required.organizationId();
        return transactions.required(() -> {
            authorization.requireCapability(
                    access, organizationId, OrganizationCapability.AUDIT_READ_ORG);
            return directory.search(required);
        });
    }
}
