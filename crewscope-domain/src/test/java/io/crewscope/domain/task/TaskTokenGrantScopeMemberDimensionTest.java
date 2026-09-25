package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.team.TeamMemberId;
import org.junit.jupiter.api.Test;

/**
 * M9b-A07: the member authorization dimension on a Task Token scope is optional for legacy
 * construction, validated when present, and pinned through the issue overloads (ADR-038 §2).
 */
class TaskTokenGrantScopeMemberDimensionTest {

    @Test
    void legacyConstructionLeavesTheDimensionEmpty() {
        TaskCredentialGrantDomainFixture fixture = new TaskCredentialGrantDomainFixture();

        TaskCredentialIssuance issuance = fixture.issue();

        assertEquals(Optional.empty(), issuance.grant().scope().executionMemberId());
        assertEquals(
                Optional.empty(),
                issuance.grant().scope().executionMemberAuthorizationVersion());
    }

    @Test
    void issuancePinsTheMemberDimensionWhenProvided() {
        TaskCredentialGrantDomainFixture fixture = new TaskCredentialGrantDomainFixture();
        TeamMemberId memberId = TeamMemberId.generate();

        TaskCredentialIssuance issuance = TaskCredentialGrant.issue(
                TaskCredentialGrantId.generate(),
                fixture.claimedExecution,
                fixture.lease,
                fixture.policy,
                fixture.overlay,
                Set.of("repository.read"),
                List.of(fixture.providerRequest()),
                new TaskTokenJti(TaskCredentialGrantDomainFixture.JTI_VALUE),
                TaskCredentialGrantDomainFixture.EXPIRES_AT,
                fixture.base.executor,
                TaskCredentialGrantDomainFixture.ISSUED_AT,
                Optional.of(memberId),
                Optional.of(2L));

        assertEquals(Optional.of(memberId), issuance.grant().scope().executionMemberId());
        assertEquals(Optional.of(2L), issuance.grant().scope().executionMemberAuthorizationVersion());
        assertEquals(Optional.of(memberId), issuance.claims().scope().executionMemberId());
    }

    @Test
    void compactConstructorRejectsNonPositiveAuthorizationVersion() {
        TaskCredentialGrantDomainFixture fixture = new TaskCredentialGrantDomainFixture();
        TaskTokenGrantScope source = fixture.issue().grant().scope();

        DomainValidationException rejected = assertThrows(
                DomainValidationException.class,
                () -> new TaskTokenGrantScope(
                        source.workItemScope(), source.taskId(), source.taskExecutionId(),
                        source.attempt(), source.executionLeaseId(), source.environment(),
                        source.runtimeId(), source.workerId(), source.claimTokenHash(),
                        source.fencingToken(), source.executionPrincipal(),
                        source.policySnapshotId(), source.policySnapshotHash(),
                        source.safetyOverlay(), source.allowedTools(),
                        source.providerAuthorizations(),
                        Optional.of(TeamMemberId.generate()), Optional.of(0L)));

        assertTrue(rejected.getMessage().contains("executionMemberAuthorizationVersion"));
    }
}
