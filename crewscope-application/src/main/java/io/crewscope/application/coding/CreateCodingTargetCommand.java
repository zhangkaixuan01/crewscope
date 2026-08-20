package io.crewscope.application.coding;

import io.crewscope.domain.coding.BuildProfileReference;
import io.crewscope.domain.coding.CodingTargetAllowedPaths;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBranchName;
import java.util.Objects;

/** User-approved immutable repository target inputs for one new Coding Task. */
public record CreateCodingTargetCommand(
        RepositoryBindingId repositoryBindingId,
        RepositoryBranchName baselineRef,
        CodingTargetAllowedPaths allowedPaths,
        BuildProfileReference buildProfile) {

    public CreateCodingTargetCommand {
        repositoryBindingId = Objects.requireNonNull(repositoryBindingId, "repositoryBindingId");
        baselineRef = Objects.requireNonNull(baselineRef, "baselineRef");
        allowedPaths = Objects.requireNonNull(allowedPaths, "allowedPaths");
        buildProfile = Objects.requireNonNull(buildProfile, "buildProfile");
    }
}
