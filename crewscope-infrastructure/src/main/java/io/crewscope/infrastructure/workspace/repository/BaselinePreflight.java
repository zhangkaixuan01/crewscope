package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKind;
import io.crewscope.infrastructure.workspace.git.GitCommandError;
import io.crewscope.infrastructure.workspace.git.GitCommandException;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import java.util.Objects;

/** Captures and verifies immutable Coding baselines against managed repository facts. */
public final class BaselinePreflight {

    private final ManagedRepositoryResolver repositoryResolver;
    private final GitCommandExecutor gitCommands;

    public BaselinePreflight(
            ManagedRepositoryResolver repositoryResolver, GitCommandExecutor gitCommands) {
        this.repositoryResolver = Objects.requireNonNull(repositoryResolver, "repositoryResolver");
        this.gitCommands = Objects.requireNonNull(gitCommands, "gitCommands");
    }

    /** Resolves a current active binding and short Ref for a new CodingTargetSnapshot. */
    public BaselinePreflightResult capture(
            RepositoryBinding binding, RepositoryBranchName baselineRef) {
        RepositoryBinding activeBinding = requireActiveLocalBinding(binding);
        RepositoryBranchName ref = Objects.requireNonNull(baselineRef, "baselineRef");
        ManagedRepository repository = repositoryResolver.resolve(activeBinding.repositoryKey());
        RepositoryCommitId commit;
        try {
            commit = gitCommands.resolveBranch(repository.canonicalPath(), ref);
        } catch (GitCommandException failure) {
            throw mapReferenceFailure(failure);
        }
        return new BaselinePreflightResult(repository, ref, commit);
    }

    /** Rechecks a Ref immediately before snapshot publication and rejects a moved baseline. */
    public BaselinePreflightResult verifyExpected(
            RepositoryBinding binding,
            RepositoryBranchName baselineRef,
            RepositoryCommitId expectedCommit) {
        RepositoryCommitId expected = Objects.requireNonNull(expectedCommit, "expectedCommit");
        BaselinePreflightResult current = capture(binding, baselineRef);
        if (!current.baselineCommit().equals(expected)) {
            throw failure(
                    RepositoryPreflightError.BASELINE_MOVED,
                    "Repository baseline moved during Preflight");
        }
        return current;
    }

    /** Verifies the immutable Commit in a historical snapshot without consulting mutable Ref state. */
    public BaselinePreflightResult verifySnapshot(CodingTargetSnapshot snapshot) {
        CodingTargetSnapshot target = Objects.requireNonNull(snapshot, "snapshot");
        if (target.repositoryKind() != RepositoryKind.LOCAL_MANAGED) {
            throw failure(
                    RepositoryPreflightError.BINDING_MISMATCH,
                    "Coding target repository kind is unsupported");
        }
        ManagedRepository repository = repositoryResolver.resolve(target.repositoryKey());
        try {
            gitCommands.verifyCommit(repository.canonicalPath(), target.baselineCommit());
        } catch (GitCommandException failure) {
            RepositoryPreflightError error = failure.error() == GitCommandError.INVALID_REFERENCE
                    ? RepositoryPreflightError.COMMIT_NOT_FOUND
                    : RepositoryPreflightError.COMMAND_FAILED;
            throw failure(error, "Coding target baseline Commit validation failed");
        }
        return new BaselinePreflightResult(
                repository, target.baselineRef(), target.baselineCommit());
    }

    private static RepositoryBinding requireActiveLocalBinding(RepositoryBinding binding) {
        RepositoryBinding required = Objects.requireNonNull(binding, "binding");
        if (!required.acceptsNewTargets()) {
            throw failure(
                    RepositoryPreflightError.BINDING_INACTIVE,
                    "Repository binding is not active");
        }
        if (required.kind() != RepositoryKind.LOCAL_MANAGED) {
            throw failure(
                    RepositoryPreflightError.BINDING_MISMATCH,
                    "Repository binding kind is unsupported");
        }
        return required;
    }

    private static RepositoryPreflightException mapReferenceFailure(
            GitCommandException failure) {
        RepositoryPreflightError error = failure.error() == GitCommandError.INVALID_REFERENCE
                ? RepositoryPreflightError.REFERENCE_INVALID
                : RepositoryPreflightError.COMMAND_FAILED;
        return failure(error, "Repository baseline Ref validation failed");
    }

    private static RepositoryPreflightException failure(
            RepositoryPreflightError error, String summary) {
        return new RepositoryPreflightException(error, summary);
    }
}
