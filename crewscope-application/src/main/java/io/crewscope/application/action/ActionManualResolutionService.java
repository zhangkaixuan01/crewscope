package io.crewscope.application.action;

import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.action.ActionBundle;
import io.crewscope.domain.action.ActionDispatch;
import io.crewscope.domain.action.ActionEvidenceReference;
import io.crewscope.domain.action.ActionReceipt;
import io.crewscope.domain.action.ActionReceiptId;
import io.crewscope.domain.action.PlannedAction;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.TimeProvider;
import java.util.Objects;

/** Atomic human resolution path used later by the M5-A07 authorized API boundary. */
public final class ActionManualResolutionService {

    private final ActionDispatchRepository dispatches;
    private final ActionReceiptRepository receipts;
    private final ActionBundleRepository bundles;
    private final ActionAuthorityFactsResolver authorityResolver;
    private final ActionWorkerEventPublisher events;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public ActionManualResolutionService(
            ActionDispatchRepository dispatches,
            ActionReceiptRepository receipts,
            ActionBundleRepository bundles,
            ActionAuthorityFactsResolver authorityResolver,
            ActionWorkerEventPublisher events,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.dispatches = Objects.requireNonNull(dispatches, "dispatches");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.bundles = Objects.requireNonNull(bundles, "bundles");
        this.authorityResolver = Objects.requireNonNull(authorityResolver, "authorityResolver");
        this.events = Objects.requireNonNull(events, "events");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public ActionDispatch resolve(ResolveActionManuallyCommand command) {
        ResolveActionManuallyCommand required = Objects.requireNonNull(command, "command");
        return transactions.required(() -> resolveInTransaction(required));
    }

    private ActionDispatch resolveInTransaction(ResolveActionManuallyCommand command) {
        ActionDispatch dispatch = dispatches.findById(
                        command.organizationId(), command.dispatchId())
                .orElseThrow(() -> new IllegalStateException("Action Dispatch is unavailable"));
        if (dispatch.version() != command.expectedVersion()) {
            throw new io.crewscope.domain.shared.error.OptimisticLockConflictException(
                    "ActionDispatch", dispatch.id(), command.expectedVersion(), dispatch.version());
        }
        ActionBundle bundle = bundles.findById(command.organizationId(), dispatch.bundleId())
                .orElseThrow(() -> new IllegalStateException("Action Bundle is unavailable"));
        if (!bundle.digest().equals(dispatch.bundleDigest())) {
            throw new IllegalStateException(
                    "Action Bundle digest changed before manual resolution");
        }
        PlannedAction action = bundle.actions().stream()
                .filter(value -> value.id().equals(dispatch.actionId())
                        && value.digest().equals(dispatch.actionDigest()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Action is absent from its confirmed Bundle"));
        var facts = authorityResolver.resolveCurrent(bundle.authority());
        if (command.actor().type() != PrincipalType.USER
                || !command.actor().canAct()
                || !command.actor().scope().organizationId().equals(command.organizationId())
                || !facts.responsibility().isActive()
                || facts.responsibility().role() != ResponsibilityRole.OWNER
                || !facts.responsibility().actorPrincipalId().equals(command.actor().id())) {
            throw new DomainValidationException(
                    "actionReceipt.resolvedByPrincipalId",
                    "manual resolution requires the current active WorkItem OWNER");
        }
        var now = timeProvider.now();
        ActionReceipt candidate = ActionReceipt.manual(
                ActionReceiptId.generate(),
                dispatch,
                action,
                command.result(),
                command.externalIdentity(),
                command.targetVersion(),
                ActionEvidenceReference.hashed(command.reason().name(), command.explanation()),
                command.reason(),
                command.actor(),
                now);
        ActionReceiptInsertResult inserted = receipts.insertIfAbsent(candidate);
        ActionDispatch resolved = dispatch.resolveManually(
                dispatch.version(), inserted.receipt(), now);
        ActionDispatch committed = dispatches.update(resolved);
        if (inserted.inserted()) {
            events.receiptRecorded(inserted.receipt(), bundle, action.id().value());
        }
        events.dispatchTransitioned(committed, bundle, action.id().value());
        return committed;
    }
}
