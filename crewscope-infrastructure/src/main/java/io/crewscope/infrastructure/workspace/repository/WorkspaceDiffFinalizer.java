package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.coding.DiffArtifactRepository;
import io.crewscope.application.coding.ExecutionWorkspaceRepository;
import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.DiffArtifactId;
import io.crewscope.domain.coding.DiffArtifactWorkspaceConflictException;
import io.crewscope.domain.coding.DiffManifest;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceStatus;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.workspace.git.GitCommandException;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/** Idempotently publishes one final Diff from the exact archived baseline/delivery commit pair. */
public final class WorkspaceDiffFinalizer {

    private final ManagedRepositoryResolver repositories;
    private final GitCommandExecutor git;
    private final GitWorkspaceDiffReconciler reconciler;
    private final PatchArtifactWriter patches;
    private final DiffArtifactRepository diffs;
    private final ExecutionWorkspaceRepository workspaces;
    private final Clock clock;

    WorkspaceDiffFinalizer(
            ManagedRepositoryResolver repositories,
            GitCommandExecutor git,
            GitWorkspaceDiffReconciler reconciler,
            PatchArtifactWriter patches,
            DiffArtifactRepository diffs,
            ExecutionWorkspaceRepository workspaces,
            Clock clock) {
        this.repositories = Objects.requireNonNull(repositories, "repositories");
        this.git = Objects.requireNonNull(git, "git");
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
        this.patches = Objects.requireNonNull(patches, "patches");
        this.diffs = Objects.requireNonNull(diffs, "diffs");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Reuses an identical published result; a different delivery fact for the Workspace fails
     * closed. Artifact content is written before relational metadata becomes observable.
     */
    public DiffArtifact finalizeDiff(
            ExecutionWorkspace workspace,
            CodingTargetSnapshot codingTarget,
            WorkspacePolicy policy,
            WorktreeArchiveResult archived,
            Principal actor,
            Optional<DiffManifest> liveManifest) {
        ExecutionWorkspace current = requireContext(
                workspace, codingTarget, policy, archived, actor);
        Optional<DiffArtifact> existing = findExisting(current);
        if (existing.isPresent()) {
            return requireSameDelivery(existing.orElseThrow(), current, archived.deliveryCommit());
        }
        ManagedRepository repository = repositories.resolve(current.repositoryKey());
        requireDelivery(repository, current, archived);
        WorkspaceDiffSnapshot snapshot = reconciler.reconcileCommits(
                current,
                repository,
                policy,
                archived.deliveryCommit(),
                Objects.requireNonNull(liveManifest, "liveManifest"));
        try {
            var patch = patches.write(current, actor, snapshot);
            // The artifact is not published as final metadata until all mutable context is re-read.
            ExecutionWorkspace reloaded = requireUnchanged(current);
            requireDelivery(repository, reloaded, archived);
            DiffArtifact artifact = DiffArtifact.publishFinal(
                    DiffArtifactId.generate(),
                    reloaded,
                    codingTarget,
                    archived.deliveryCommit(),
                    snapshot.manifest(),
                    patch,
                    actor,
                    UtcTimestamp.from(clock.instant()));
            return diffs.create(artifact);
        } catch (DiffArtifactWorkspaceConflictException raced) {
            return findExisting(current)
                    .map(found -> requireSameDelivery(
                            found, current, archived.deliveryCommit()))
                    .orElseThrow(() -> publicationFailure(raced));
        } catch (WorkspaceDiffException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw publicationFailure(failure);
        }
    }

    private ExecutionWorkspace requireContext(
            ExecutionWorkspace workspace,
            CodingTargetSnapshot target,
            WorkspacePolicy policy,
            WorktreeArchiveResult archived,
            Principal actor) {
        ExecutionWorkspace current = Objects.requireNonNull(workspace, "workspace");
        CodingTargetSnapshot codingTarget = Objects.requireNonNull(target, "codingTarget");
        WorkspacePolicy effective = Objects.requireNonNull(policy, "policy");
        WorktreeArchiveResult delivery = Objects.requireNonNull(archived, "archived");
        Principal principal = Objects.requireNonNull(actor, "actor");
        boolean outsideTeam = principal.scope().teamId().isPresent()
                && principal.scope().teamId().filter(current.scope().teamId()::equals).isEmpty();
        if (current.status() != ExecutionWorkspaceStatus.FINALIZING
                || !current.id().equals(delivery.workspaceId())
                || !current.archiveReference().equals(delivery.archiveReference())
                || !current.scope().equals(codingTarget.scope())
                || !current.taskId().equals(codingTarget.taskId())
                || !current.codingTarget().equals(codingTarget.reference())
                || !current.baselineCommit().equals(codingTarget.baselineCommit())
                || !current.scope().equals(effective.scope())
                || !current.taskExecutionId().equals(effective.taskExecutionId())
                || current.attempt() != effective.attempt()
                || !current.codingTarget().equals(effective.codingTarget())
                || !principal.canAct()
                || !principal.scope().organizationId().equals(current.scope().organizationId())
                || outsideTeam) {
            throw new WorkspaceDiffException(
                    WorkspaceDiffError.INVALID_CONTEXT,
                    "Final Diff facts do not match the Workspace context");
        }
        return requireUnchanged(current);
    }

    private ExecutionWorkspace requireUnchanged(ExecutionWorkspace expected) {
        ExecutionWorkspace current = workspaces.findById(
                        expected.scope().organizationId(),
                        expected.scope().teamId(),
                        expected.scope().projectId(),
                        expected.id())
                .orElseThrow(() -> new WorkspaceDiffException(
                        WorkspaceDiffError.INVALID_CONTEXT,
                        "ExecutionWorkspace no longer exists"));
        if (current.status() != ExecutionWorkspaceStatus.FINALIZING
                || current.version() != expected.version()
                || !current.fingerprint().equals(expected.fingerprint())) {
            throw new WorkspaceDiffException(
                    WorkspaceDiffError.INVALID_CONTEXT,
                    "ExecutionWorkspace changed during Diff finalization");
        }
        return current;
    }

    private void requireDelivery(
            ManagedRepository repository,
            ExecutionWorkspace workspace,
            WorktreeArchiveResult archived) {
        try {
            RepositoryCommitId delivery = archived.deliveryCommit();
            Optional<RepositoryCommitId> reference = git.findArchiveReference(
                    repository.canonicalPath(), workspace.archiveReference());
            if (reference.filter(delivery::equals).isEmpty()
                    || !git.hasSingleParent(
                            repository.canonicalPath(), delivery, workspace.baselineCommit())
                    || !git.commitTreeId(repository.canonicalPath(), delivery)
                            .equals(archived.deliveryTree())) {
                throw new WorkspaceDiffException(
                        WorkspaceDiffError.FINALIZATION_CONFLICT,
                        "Delivery Commit does not match the archived Workspace tree");
            }
        } catch (WorkspaceDiffException failure) {
            throw failure;
        } catch (GitCommandException failure) {
            throw new WorkspaceDiffException(
                    WorkspaceDiffError.COMMAND_FAILED,
                    "Delivery Commit could not be verified");
        }
    }

    private Optional<DiffArtifact> findExisting(ExecutionWorkspace workspace) {
        return diffs.findByWorkspace(
                workspace.scope().organizationId(),
                workspace.scope().teamId(),
                workspace.scope().projectId(),
                workspace.id());
    }

    private static DiffArtifact requireSameDelivery(
            DiffArtifact existing,
            ExecutionWorkspace workspace,
            RepositoryCommitId deliveryCommit) {
        if (!existing.executionWorkspaceId().equals(workspace.id())
                || !existing.baselineCommit().equals(workspace.baselineCommit())
                || !existing.deliveryCommit().equals(deliveryCommit)
                || !existing.codingTarget().equals(workspace.codingTarget())) {
            throw new WorkspaceDiffException(
                    WorkspaceDiffError.FINALIZATION_CONFLICT,
                    "A different final Diff already exists for the Workspace");
        }
        return existing;
    }

    private static WorkspaceDiffException publicationFailure(Throwable cause) {
        return new WorkspaceDiffException(
                WorkspaceDiffError.ARTIFACT_PUBLICATION_FAILED,
                "Final DiffArtifact could not be published");
    }
}
