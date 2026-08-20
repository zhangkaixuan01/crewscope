package io.crewscope.infrastructure.workspace.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.coding.RepositoryBindingPreflightError;
import io.crewscope.application.coding.RepositoryBindingPreflightException;
import io.crewscope.application.coding.RepositoryBindingPreflightResult;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ManagedRepositoryBindingPreflightAdapterM4A01Test {

    @Test
    void removesResolvedHostPathFromTheApplicationResult() {
        BaselinePreflight preflight = mock(BaselinePreflight.class);
        RepositoryBinding binding = mock(RepositoryBinding.class);
        RepositoryBranchName branch = new RepositoryBranchName("main");
        RepositoryKey key = new RepositoryKey("crewscope");
        RepositoryCommitId commit =
                new RepositoryCommitId("0123456789abcdef0123456789abcdef01234567");
        ManagedRepository managed = new ManagedRepository(key, Path.of("/private/managed/crewscope.git"));
        when(preflight.capture(binding, branch))
                .thenReturn(new BaselinePreflightResult(managed, branch, commit));

        RepositoryBindingPreflightResult result =
                new ManagedRepositoryBindingPreflightAdapter(preflight)
                        .preflight(binding, branch);

        assertEquals(key, result.repositoryKey());
        assertEquals(commit, result.baselineCommit());
        assertFalse(result.toString().contains("/private/managed"));
    }

    @Test
    void mapsInfrastructureFailuresToStablePathFreeCategories() {
        BaselinePreflight preflight = mock(BaselinePreflight.class);
        RepositoryBinding binding = mock(RepositoryBinding.class);
        RepositoryBranchName branch = new RepositoryBranchName("main");
        when(preflight.capture(binding, branch))
                .thenThrow(new RepositoryPreflightException(
                        RepositoryPreflightError.OWNER_MISMATCH,
                        "Repository owner does not match /private/secret"));

        RepositoryBindingPreflightException failure = assertThrows(
                RepositoryBindingPreflightException.class,
                () -> new ManagedRepositoryBindingPreflightAdapter(preflight)
                        .preflight(binding, branch));

        assertEquals(RepositoryBindingPreflightError.REPOSITORY_INVALID, failure.error());
        assertEquals("Managed repository Preflight failed", failure.getMessage());
        assertFalse(failure.getMessage().contains("/private/secret"));
    }
}
