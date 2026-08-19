package io.crewscope.agentscope.coding;

import io.crewscope.application.coding.output.RepositoryAnalysisV1;
import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.DiffManifest;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.coding.WorkspacePolicy;
import java.util.Objects;
import java.util.Optional;

/** Platform-owned Coding facts observed after one finite Specialist call. */
public record CodingSpecialistAuthority(
        CodingTargetSnapshot target,
        ExecutionWorkspace workspace,
        WorkspacePolicy policy,
        RepositoryAnalysisV1 repositoryAnalysis,
        DiffManifest diffManifest,
        Optional<TestEvidence> testEvidence,
        Optional<DiffArtifact> finalDiffArtifact) {

    public CodingSpecialistAuthority {
        target = Objects.requireNonNull(target, "target");
        workspace = Objects.requireNonNull(workspace, "workspace");
        policy = Objects.requireNonNull(policy, "policy");
        repositoryAnalysis = Objects.requireNonNull(repositoryAnalysis, "repositoryAnalysis");
        diffManifest = Objects.requireNonNull(diffManifest, "diffManifest");
        testEvidence = Objects.requireNonNull(testEvidence, "testEvidence");
        finalDiffArtifact = Objects.requireNonNull(finalDiffArtifact, "finalDiffArtifact");
    }
}
