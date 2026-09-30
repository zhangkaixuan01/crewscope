package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.coding.RepositoryBindingPreflightError;
import io.crewscope.application.coding.RepositoryBindingPreflightException;
import io.crewscope.application.coding.RepositoryBindingPreflightPort;
import io.crewscope.application.coding.RepositoryBindingPreflightResult;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBranchName;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Adapts host-local managed repository validation to the application's path-free contract. */
public final class ManagedRepositoryBindingPreflightAdapter
        implements RepositoryBindingPreflightPort {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManagedRepositoryBindingPreflightAdapter.class);

    private final BaselinePreflight baselinePreflight;

    public ManagedRepositoryBindingPreflightAdapter(BaselinePreflight baselinePreflight) {
        this.baselinePreflight = Objects.requireNonNull(baselinePreflight, "baselinePreflight");
    }

    @Override
    public RepositoryBindingPreflightResult preflight(
            RepositoryBinding binding, RepositoryBranchName baselineRef) {
        try {
            BaselinePreflightResult result = baselinePreflight.capture(binding, baselineRef);
            return new RepositoryBindingPreflightResult(
                    result.repository().repositoryKey(),
                    result.baselineRef(),
                    result.baselineCommit());
        } catch (RepositoryPreflightException failure) {
            // The summary is a fixed secret-free sentence naming the failing check; logging it
            // keeps the 422 response stable while making the rejected phase diagnosable.
            LOGGER.warn("Managed repository Preflight rejected a target: {} ({})",
                    failure.error(), failure.getMessage());
            throw new RepositoryBindingPreflightException(
                    map(failure.error()), "Managed repository Preflight failed");
        }
    }

    private static RepositoryBindingPreflightError map(RepositoryPreflightError error) {
        return switch (error) {
            case REPOSITORY_NOT_FOUND -> RepositoryBindingPreflightError.REPOSITORY_NOT_FOUND;
            case REFERENCE_INVALID, COMMIT_NOT_FOUND ->
                    RepositoryBindingPreflightError.REFERENCE_INVALID;
            case COMMAND_FAILED, BASELINE_MOVED ->
                    RepositoryBindingPreflightError.COMMAND_FAILED;
            case MANAGED_ROOT_INVALID,
                    PATH_ESCAPE,
                    SYMLINK_REJECTED,
                    OWNER_MISMATCH,
                    NOT_BARE_REPOSITORY,
                    BINDING_INACTIVE,
                    BINDING_MISMATCH -> RepositoryBindingPreflightError.REPOSITORY_INVALID;
        };
    }
}
