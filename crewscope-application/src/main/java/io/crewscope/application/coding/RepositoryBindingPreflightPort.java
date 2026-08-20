package io.crewscope.application.coding;

import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBranchName;

/** Application Port for resolving one managed repository Ref without exposing its host path. */
@FunctionalInterface
public interface RepositoryBindingPreflightPort {

    RepositoryBindingPreflightResult preflight(
            RepositoryBinding binding, RepositoryBranchName baselineRef);
}
