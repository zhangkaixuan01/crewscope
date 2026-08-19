package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.artifact.ArtifactPurgeRequest;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded startup recovery and retention cleanup budgets for one Worker process. */
@ConfigurationProperties("crewscope.coding.recovery")
public class CodingWorkspaceStartupProperties {

    private int recoveryBatchSize = 100;
    private int retentionBatchSize = 100;
    private int artifactPurgeBatchSize = 100;

    public int getRecoveryBatchSize() {
        return recoveryBatchSize;
    }

    public void setRecoveryBatchSize(int recoveryBatchSize) {
        this.recoveryBatchSize = recoveryBatchSize;
    }

    public int getRetentionBatchSize() {
        return retentionBatchSize;
    }

    public void setRetentionBatchSize(int retentionBatchSize) {
        this.retentionBatchSize = retentionBatchSize;
    }

    public int getArtifactPurgeBatchSize() {
        return artifactPurgeBatchSize;
    }

    public void setArtifactPurgeBatchSize(int artifactPurgeBatchSize) {
        this.artifactPurgeBatchSize = artifactPurgeBatchSize;
    }

    int requiredRecoveryBatchSize() {
        return workspaceBatch(recoveryBatchSize, "Workspace recovery batch size");
    }

    int requiredRetentionBatchSize() {
        return workspaceBatch(retentionBatchSize, "Workspace retention batch size");
    }

    int requiredArtifactPurgeBatchSize() {
        if (artifactPurgeBatchSize < 1
                || artifactPurgeBatchSize > ArtifactPurgeRequest.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "Artifact purge batch size must be between 1 and "
                            + ArtifactPurgeRequest.MAX_BATCH_SIZE);
        }
        return artifactPurgeBatchSize;
    }

    private static int workspaceBatch(int value, String name) {
        if (value < 1 || value > 1_000) {
            throw new IllegalArgumentException(name + " must be between 1 and 1000");
        }
        return value;
    }
}
