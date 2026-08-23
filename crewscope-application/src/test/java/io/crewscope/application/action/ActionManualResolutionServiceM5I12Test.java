package io.crewscope.application.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.action.ActionAuthorityFacts;
import io.crewscope.domain.action.ActionBundle;
import io.crewscope.domain.action.ActionBundleDigest;
import io.crewscope.domain.action.ActionBundleId;
import io.crewscope.domain.action.ActionDigest;
import io.crewscope.domain.action.ActionDispatch;
import io.crewscope.domain.action.ActionDispatchId;
import io.crewscope.domain.action.ActionDispatchStatus;
import io.crewscope.domain.action.ActionIdempotencyKey;
import io.crewscope.domain.action.ActionReceipt;
import io.crewscope.domain.action.ActionReceiptResult;
import io.crewscope.domain.action.ManualResolutionReason;
import io.crewscope.domain.action.PlannedAction;
import io.crewscope.domain.action.PlannedActionId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** M5-I12 application boundary proof for irreversible OWNER-only manual conclusions. */
class ActionManualResolutionServiceM5I12Test {

    @Test
    void currentOwnerRecordsTheSoleManualReceiptAndTerminalDispatch() {
        Fixture fixture = new Fixture();

        ActionDispatch resolved = fixture.service().resolve(fixture.command(fixture.owner, 7));

        assertEquals(ActionDispatchStatus.FAILED, resolved.status());
        verify(fixture.receipts).insertIfAbsent(any());
        verify(fixture.events).receiptRecorded(any(), any(), any());
        verify(fixture.events).dispatchTransitioned(any(), any(), any());
    }

    @Test
    void staleVersionIsRejectedBeforeAnyReceiptIsCreated() {
        Fixture fixture = new Fixture();

        assertThrows(OptimisticLockConflictException.class,
                () -> fixture.service().resolve(fixture.command(fixture.owner, 6)));

        verify(fixture.receipts, never()).insertIfAbsent(any());
    }

    @Test
    void nonOwnerIsRejectedBeforeAnyReceiptIsCreated() {
        Fixture fixture = new Fixture();
        Principal teammate = fixture.principal(PrincipalId.generate(), "Teammate");

        assertThrows(DomainValidationException.class,
                () -> fixture.service().resolve(fixture.command(teammate, 7)));

        verify(fixture.receipts, never()).insertIfAbsent(any());
    }

    @Test
    void agentPrincipalCannotCreateAHumanManualConclusionEvenWithTheOwnerId() {
        Fixture fixture = new Fixture();
        Principal agent = fixture.agentPrincipal(fixture.owner.id());

        assertThrows(DomainValidationException.class,
                () -> fixture.service().resolve(fixture.command(agent, 7)));

        verify(fixture.receipts, never()).insertIfAbsent(any());
    }

    @Test
    void changedBundleDigestIsRejectedBeforeCurrentAuthorityResolution() {
        Fixture fixture = new Fixture();
        when(fixture.bundle.digest()).thenReturn(
                new ActionBundleDigest(TaskFactHash.sha256("changed-bundle")));

        assertThrows(IllegalStateException.class,
                () -> fixture.service().resolve(fixture.command(fixture.owner, 7)));

        verify(fixture.authorityResolver, never()).resolveCurrent(any());
        verify(fixture.receipts, never()).insertIfAbsent(any());
    }

    private static final class Fixture {

        private final UtcTimestamp now = UtcTimestamp.parse("2026-08-24T09:00:00Z");
        private final OrganizationId organizationId = OrganizationId.generate();
        private final TeamId teamId = TeamId.generate();
        private final WorkItemScope scope = new WorkItemScope(
                organizationId, teamId, WorkspaceId.generate(), WorkProjectId.generate());
        private final Principal owner = principal(PrincipalId.generate(), "Owner");
        private final ActionBundleId bundleId = ActionBundleId.generate();
        private final ActionBundleDigest bundleDigest =
                new ActionBundleDigest(TaskFactHash.sha256("bundle"));
        private final PlannedActionId actionId = PlannedActionId.generate();
        private final ActionDigest actionDigest = new ActionDigest(TaskFactHash.sha256("action"));
        private final ActionDispatchId dispatchId = ActionDispatchId.generate();
        private final PlannedAction action = mock(PlannedAction.class);
        private final ActionBundle bundle = mock(ActionBundle.class);
        private final ActionDispatch dispatch = mock(ActionDispatch.class);
        private final ActionDispatch terminal = mock(ActionDispatch.class);
        private final ActionDispatchRepository dispatches = mock(ActionDispatchRepository.class);
        private final ActionReceiptRepository receipts = mock(ActionReceiptRepository.class);
        private final ActionBundleRepository bundles = mock(ActionBundleRepository.class);
        private final ActionAuthorityFactsResolver authorityResolver =
                mock(ActionAuthorityFactsResolver.class);
        private final ActionWorkerEventPublisher events = mock(ActionWorkerEventPublisher.class);

        private Fixture() {
            when(action.id()).thenReturn(actionId);
            when(action.digest()).thenReturn(actionDigest);
            when(bundle.id()).thenReturn(bundleId);
            when(bundle.digest()).thenReturn(bundleDigest);
            when(bundle.actions()).thenReturn(List.of(action));
            when(dispatch.id()).thenReturn(dispatchId);
            when(dispatch.scope()).thenReturn(scope);
            when(dispatch.bundleId()).thenReturn(bundleId);
            when(dispatch.bundleDigest()).thenReturn(bundleDigest);
            when(dispatch.actionId()).thenReturn(actionId);
            when(dispatch.actionDigest()).thenReturn(actionDigest);
            when(dispatch.idempotencyKey()).thenReturn(ActionIdempotencyKey.derive(
                    organizationId, bundleId, actionId, actionDigest));
            when(dispatch.version()).thenReturn(7L);
            when(dispatch.status()).thenReturn(ActionDispatchStatus.MANUAL_REVIEW);
            when(dispatch.audit()).thenReturn(AuditMetadata.createdBy(owner.id(), now));
            when(terminal.status()).thenReturn(ActionDispatchStatus.FAILED);
            when(dispatches.findById(organizationId, dispatchId))
                    .thenReturn(Optional.of(dispatch));
            when(bundles.findById(organizationId, bundleId)).thenReturn(Optional.of(bundle));
            ResponsibilityAssignment responsibility = mock(ResponsibilityAssignment.class);
            when(responsibility.isActive()).thenReturn(true);
            when(responsibility.role()).thenReturn(ResponsibilityRole.OWNER);
            when(responsibility.actorPrincipalId()).thenReturn(owner.id());
            ActionAuthorityFacts facts = mock(ActionAuthorityFacts.class);
            when(facts.responsibility()).thenReturn(responsibility);
            when(authorityResolver.resolveCurrent(any())).thenReturn(facts);
            when(receipts.insertIfAbsent(any())).thenAnswer(invocation ->
                    new ActionReceiptInsertResult(true, invocation.getArgument(0)));
            when(dispatch.resolveManually(anyLong(), any(), any())).thenReturn(terminal);
            when(dispatches.update(terminal)).thenReturn(terminal);
        }

        private Principal principal(PrincipalId id, String displayName) {
            return Principal.create(
                    id,
                    PrincipalScope.team(organizationId, teamId),
                    PrincipalType.USER,
                    Optional.empty(),
                    displayName,
                    Optional.empty(),
                    PrincipalVisibility.TEAM,
                    now);
        }

        private Principal agentPrincipal(PrincipalId id) {
            return Principal.create(
                    id,
                    PrincipalScope.team(organizationId, teamId),
                    PrincipalType.SPECIALIST_AGENT,
                    Optional.of(PrincipalId.generate()),
                    "Agent owner",
                    Optional.empty(),
                    PrincipalVisibility.TEAM,
                    now);
        }

        private ResolveActionManuallyCommand command(Principal actor, long expectedVersion) {
            return new ResolveActionManuallyCommand(
                    organizationId,
                    dispatchId,
                    expectedVersion,
                    ActionReceiptResult.MANUALLY_FAILED,
                    Optional.empty(),
                    Optional.empty(),
                    ManualResolutionReason.NO_EXTERNAL_OBJECT_VERIFIED,
                    "GitHub audit and repository query show no external object.",
                    actor);
        }

        private ActionManualResolutionService service() {
            return new ActionManualResolutionService(
                    dispatches,
                    receipts,
                    bundles,
                    authorityResolver,
                    events,
                    new DirectTransactions(),
                    () -> now);
        }
    }

    private static final class DirectTransactions implements TransactionExecutor {

        @Override
        public <T> T required(java.util.function.Supplier<T> operation) {
            return operation.get();
        }
    }
}
