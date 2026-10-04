package io.crewscope.application.retrieval;

import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.retrieval.InjectionManifest;
import io.crewscope.domain.retrieval.ManifestSourceKey;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The I02c reference evidence service: reads back the sealed manifests of one
 * execution, records a member's "not applicable" feedback against the injected set,
 * and files the model's claimed-reference receipts — each reconciling against the
 * sealed manifests alone. Visibility is the execution-detail face (any member who
 * may see the execution may see its evidence), the ownership check runs before any
 * payload evaluation so foreign tenants always see the same 404, and feedback stays
 * the member's own opinion: only the requesting member's rows ever leave this
 * service. The endpoints are never gated on the injection switches — sealed evidence
 * is a historical fact (I01c's "closed gate keeps the read side" precedent).
 */
public final class InjectionReferenceService {

    private final WorkItemAccessPolicy accessPolicy;
    private final TaskRepository taskRepository;
    private final TaskExecutionRepository executionRepository;
    private final InjectionManifestRepository manifests;
    private final InjectionReferenceRepository references;
    private final TransactionExecutor transactionExecutor;
    private final TimeProvider timeProvider;

    public InjectionReferenceService(
            WorkItemAccessPolicy accessPolicy,
            TaskRepository taskRepository,
            TaskExecutionRepository executionRepository,
            InjectionManifestRepository manifests,
            InjectionReferenceRepository references,
            TransactionExecutor transactionExecutor,
            TimeProvider timeProvider) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.executionRepository =
                Objects.requireNonNull(executionRepository, "executionRepository");
        this.manifests = Objects.requireNonNull(manifests, "manifests");
        this.references = Objects.requireNonNull(references, "references");
        this.transactionExecutor =
                Objects.requireNonNull(transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** The read face: every sealed manifest, the caller's own feedback, all receipts. */
    public InjectionReferenceView view(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId) {
        return transactionExecutor.required(() -> {
            TaskExecution execution = requireExecution(context, organizationId, teamId,
                    taskId, executionId);
            return new InjectionReferenceView(
                    manifests.findByExecution(organizationId, teamId, execution.id()),
                    references.findFeedback(organizationId, teamId, execution.id(),
                            context.actor().id()),
                    references.findClaimed(organizationId, teamId, execution.id()));
        });
    }

    /**
     * Records "not applicable" for one source key. The key must sit in the INJECTED
     * union of the execution's sealed manifests — the budget-cut candidates never
     * reached a prompt and are not judgement targets. Structurally idempotent: the
     * same member re-judging the same evidence replays the stored row.
     */
    public InjectionReferenceFeedback submitFeedback(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            ManifestSourceKey source) {
        return transactionExecutor.required(() -> {
            TaskExecution execution = requireExecution(context, organizationId, teamId,
                    taskId, executionId);
            Set<ManifestSourceKey> injectedUnion = new HashSet<>();
            for (InjectionManifest manifest
                    : manifests.findByExecution(organizationId, teamId, execution.id())) {
                manifest.references().stream()
                        .filter(reference -> reference.stage()
                                == io.crewscope.domain.retrieval.ManifestSourceStage.INJECTED)
                        .map(ManifestSourceKey::of)
                        .forEach(injectedUnion::add);
            }
            if (!injectedUnion.contains(source)) {
                throw new FeedbackReferenceOutsideManifestException(execution.id(), source);
            }
            return references.record(new InjectionReferenceFeedback(
                    execution.id(), source, context.actor().id(),
                    InjectionFeedbackKind.NOT_APPLICABLE, timeProvider.now()));
        });
    }

    /**
     * Files the claimed-reference receipt for one attempt. Every claimed key must sit
     * in that attempt's INJECTED set — declared references cannot exceed the manifest
     * (plan §4.2) — and the receipt must reconcile against a sealed manifest. An
     * identical set replays; a different set for the same attempt is a conflict.
     *
     * <p>Deliberately not wrapped in a transaction: the repository's conflict path
     * trips the unique key and then re-reads on its own connection (a PostgreSQL
     * transaction that hit a unique violation is aborted — no statement runs in it),
     * and a REQUIRED wrapper would drag that read-back into the aborted transaction.
     * The reads this method makes guard per request and the manifest it anchors against
     * is sealed and immutable, so no atomicity is lost; replay safety is the uk's.
     */
    public InjectionClaimedReferences submitClaimed(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            int attempt,
            List<ManifestSourceKey> claimed) {
        TaskExecution execution = requireExecution(context, organizationId, teamId,
                taskId, executionId);
        InjectionManifest manifest = manifests
                .findByAttempt(organizationId, teamId, execution.id(), attempt)
                .orElseThrow(() ->
                        new InjectionManifestNotSealedException(execution.id(), attempt));
        Set<ManifestSourceKey> injected = new HashSet<>();
        manifest.references().stream()
                .filter(reference -> reference.stage()
                        == io.crewscope.domain.retrieval.ManifestSourceStage.INJECTED)
                .map(ManifestSourceKey::of)
                .forEach(injected::add);
        List<ManifestSourceKey> outside = claimed.stream()
                .filter(key -> !injected.contains(key))
                .toList();
        if (!outside.isEmpty()) {
            throw new ClaimedReferenceOutsideManifestException(
                    execution.id(), attempt, outside);
        }
        return references.recordClaimed(new InjectionClaimedReferences(
                execution.id(), attempt, claimed, timeProvider.now()));
    }

    private TaskExecution requireExecution(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId) {
        accessPolicy.requireVisibleTeam(context, organizationId, teamId);
        Task task = taskRepository.findById(organizationId, taskId)
                .filter(value -> value.scope().teamId().equals(teamId))
                .orElseThrow(() -> new AggregateNotFoundException("Task", taskId));
        return executionRepository.findById(organizationId, executionId)
                .filter(value -> value.taskId().equals(task.id()))
                .filter(value -> value.scope().equals(task.scope()))
                .orElseThrow(() -> new AggregateNotFoundException(
                        "TaskExecution", executionId));
    }

    /** Everything the read face needs, assembled inside one transaction. */
    public record InjectionReferenceView(
            List<InjectionManifest> manifests,
            List<InjectionReferenceFeedback> memberFeedback,
            List<InjectionClaimedReferences> claimed) {
    }
}
