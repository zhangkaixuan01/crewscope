package io.crewscope.application.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.AccountStatus;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.organization.role.OrganizationRole;
import io.crewscope.domain.organization.role.OrganizationRoleGrant;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * The M13b-A01 permission boundary as executable truth: the roster read stays an organization
 * audit-grade fact — an ORG_AUDITOR keeps it, a plain member is denied before the directory is
 * ever queried — and the whole read runs inside one transaction.
 */
class OrganizationMemberDirectoryQueryServiceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-19T08:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final PrincipalId principalId = PrincipalId.generate();
    private final OrganizationRoleGrantRepository grants =
            mock(OrganizationRoleGrantRepository.class);
    private final OrganizationMemberDirectoryRepository directory =
            mock(OrganizationMemberDirectoryRepository.class);

    private Principal actor() {
        return Principal.create(
                principalId,
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Actor",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
    }

    private OrganizationMemberDirectoryQueryService service(TransactionExecutor transactions) {
        return new OrganizationMemberDirectoryQueryService(
                new OrganizationAuthorization(grants), directory, transactions);
    }

    @Test
    void anAuditorReadsTheRosterInsideOneTransaction() {
        when(grants.findActiveByPrincipal(organizationId, principalId))
                .thenReturn(List.of(OrganizationRoleGrant.grant(
                        organizationId, principalId, OrganizationRole.ORG_AUDITOR,
                        principalId, null, NOW)));
        OrganizationMemberDirectoryPage page = new OrganizationMemberDirectoryPage(
                List.of(new OrganizationMemberDirectoryEntry(
                        UserAccountId.generate(),
                        PrincipalId.generate(),
                        "Alice",
                        "alice",
                        AccountStatus.ACTIVE,
                        List.of())),
                OptionalInt.empty(),
                Optional.empty());
        when(directory.search(org.mockito.ArgumentMatchers.any())).thenReturn(page);
        int[] requiredCalls = {0};
        TransactionExecutor transactions = new TransactionExecutor() {
            @Override
            public <T> T required(java.util.function.Supplier<T> operation) {
                requiredCalls[0]++;
                return operation.get();
            }
        };
        OrganizationMemberDirectoryQuery query = new OrganizationMemberDirectoryQuery(
                organizationId, Optional.empty(), Optional.empty(), 0, 20);

        assertSame(page, service(transactions).search(new OrganizationAccessContext(actor()), query));
        assertEquals(1, requiredCalls[0], "the read joins exactly one transaction");
        verify(directory).search(query);
    }

    @Test
    void aMemberWithoutOrgGrantsIsDeniedBeforeTheDirectoryIsQueried() {
        when(grants.findActiveByPrincipal(organizationId, principalId)).thenReturn(List.of());

        PolicyDeniedException denied = assertThrows(
                PolicyDeniedException.class,
                () -> service(new DirectTransactions()).search(
                        new OrganizationAccessContext(actor()),
                        new OrganizationMemberDirectoryQuery(
                                organizationId, Optional.empty(), Optional.empty(), 0, 20)));
        assertEquals("read organization audit", denied.error().details().get("action"));
        verifyNoInteractions(directory);
    }

    private static final class DirectTransactions implements TransactionExecutor {

        @Override
        public <T> T required(java.util.function.Supplier<T> operation) {
            return operation.get();
        }
    }
}
