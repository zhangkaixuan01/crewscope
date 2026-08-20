package io.crewscope.application.coding;

import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.TestEvidence;

/** Publishes Coding facts into the durable member-visible Task timeline. */
public interface CodingTaskTimelinePublisher {

    CodingTaskTimelinePublisher NO_OP = new CodingTaskTimelinePublisher() {
        @Override
        public void workspaceChanged(ExecutionWorkspace workspace) {}

        @Override
        public void workspaceDiffChanged(WorkspaceDiffTimelineChange change) {}

        @Override
        public void testEvidencePublished(TestEvidence evidence) {}

        @Override
        public void finalDiffArtifactPublished(DiffArtifact artifact) {}
    };

    void workspaceChanged(ExecutionWorkspace workspace);

    void workspaceDiffChanged(WorkspaceDiffTimelineChange change);

    void testEvidencePublished(TestEvidence evidence);

    void finalDiffArtifactPublished(DiffArtifact artifact);
}
