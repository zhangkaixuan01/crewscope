package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import java.nio.file.Path;
import java.util.Objects;

/** Typed infrastructure bridge that keeps managed source repository host paths encapsulated. */
public final class ManagedRepositoryDeliveryAccess {

    private final ManagedRepositoryResolver resolver;
    private final GitCommandExecutor gitCommands;

    public ManagedRepositoryDeliveryAccess(
            ManagedRepositoryResolver resolver, GitCommandExecutor gitCommands) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.gitCommands = Objects.requireNonNull(gitCommands, "gitCommands");
    }

    /** Verifies immutable lineage and imports the complete delivery object graph into a Mirror. */
    public boolean verifyAndImport(
            RepositoryKey repositoryKey,
            RepositoryCommitId baseline,
            RepositoryCommitId delivery,
            Path mirror) {
        ManagedRepository source = resolver.resolve(
                Objects.requireNonNull(repositoryKey, "repositoryKey"));
        Path sourcePath = source.canonicalPath();
        RepositoryCommitId requiredBaseline = Objects.requireNonNull(baseline, "baseline");
        RepositoryCommitId requiredDelivery = Objects.requireNonNull(delivery, "delivery");
        try {
            gitCommands.verifyCommit(sourcePath, requiredBaseline);
        } catch (RuntimeException failure) {
            throw new ManagedDeliverySourceException(
                    ManagedDeliverySourceError.BASELINE_UNAVAILABLE,
                    "Confirmed Git baseline is unavailable");
        }
        try {
            gitCommands.verifyCommit(sourcePath, requiredDelivery);
            boolean descendant = gitCommands.isAncestor(
                    sourcePath, requiredBaseline, requiredDelivery);
            if (!descendant) {
                return false;
            }
        } catch (RuntimeException failure) {
            throw new ManagedDeliverySourceException(
                    ManagedDeliverySourceError.DELIVERY_UNAVAILABLE,
                    "Confirmed Git delivery is unavailable");
        }
        try {
            gitCommands.fetchLocalCommit(
                    Objects.requireNonNull(mirror, "mirror"), sourcePath, requiredDelivery);
            return true;
        } catch (RuntimeException failure) {
            throw new ManagedDeliverySourceException(
                    ManagedDeliverySourceError.MIRROR_IMPORT_FAILED,
                    "Confirmed Git delivery could not be imported");
        }
    }
}
