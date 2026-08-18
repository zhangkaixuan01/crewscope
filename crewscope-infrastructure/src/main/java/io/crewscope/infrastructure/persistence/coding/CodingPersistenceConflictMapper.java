package io.crewscope.infrastructure.persistence.coding;

import io.crewscope.domain.coding.CodingCheckpoint;
import io.crewscope.domain.coding.CodingCheckpointSequenceConflictException;
import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.CodingTargetSnapshotRevisionConflictException;
import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.CommandEvidenceSequenceConflictException;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.DiffArtifactWorkspaceConflictException;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceAttemptConflictException;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingKeyConflictException;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.coding.TestEvidenceSequenceConflictException;
import java.sql.SQLException;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;

/** Converts PostgreSQL uniqueness contracts into stable Coding domain conflicts. */
final class CodingPersistenceConflictMapper {

    private CodingPersistenceConflictMapper() {}

    static RuntimeException repositoryBinding(
            DataIntegrityViolationException failure, RepositoryBinding binding) {
        if (hasConstraint(failure, "uk_repository_binding_project_key")) {
            return new RepositoryBindingKeyConflictException(
                    binding.scope().workProjectId(), binding.repositoryKey());
        }
        return failure;
    }

    static RuntimeException codingTarget(
            DataIntegrityViolationException failure, CodingTargetSnapshot snapshot) {
        if (hasConstraint(failure, "uk_coding_target_snapshot_revision")) {
            return new CodingTargetSnapshotRevisionConflictException(
                    snapshot.taskId(), snapshot.revision());
        }
        return failure;
    }

    static RuntimeException executionWorkspace(
            DataIntegrityViolationException failure, ExecutionWorkspace workspace) {
        if (hasConstraint(failure, "uk_execution_workspace_attempt")) {
            return new ExecutionWorkspaceAttemptConflictException(
                    workspace.taskExecutionId(), workspace.attempt());
        }
        return failure;
    }

    static RuntimeException diffArtifact(
            DataIntegrityViolationException failure, DiffArtifact artifact) {
        if (hasConstraint(failure, "uk_diff_artifact_workspace")) {
            return new DiffArtifactWorkspaceConflictException(artifact.executionWorkspaceId());
        }
        return failure;
    }

    static RuntimeException commandEvidence(
            DataIntegrityViolationException failure, CommandEvidence evidence) {
        if (hasConstraint(failure, "uk_command_evidence_workspace_sequence")) {
            return new CommandEvidenceSequenceConflictException(
                    evidence.executionWorkspaceId(), evidence.sequence());
        }
        return failure;
    }

    static RuntimeException testEvidence(
            DataIntegrityViolationException failure, TestEvidence evidence) {
        if (hasConstraint(failure, "uk_test_evidence_workspace_sequence")) {
            return new TestEvidenceSequenceConflictException(
                    evidence.executionWorkspaceId(), evidence.sequence());
        }
        return failure;
    }

    static RuntimeException codingCheckpoint(
            DataIntegrityViolationException failure, CodingCheckpoint checkpoint) {
        if (hasConstraint(failure, "uk_coding_checkpoint_workspace_sequence")) {
            return new CodingCheckpointSequenceConflictException(
                    checkpoint.executionWorkspaceId(), checkpoint.checkpointSequence());
        }
        return failure;
    }

    /** SQLException messages retain the server constraint name after Spring translation. */
    private static boolean hasConstraint(Throwable failure, String constraintName) {
        String expected = constraintName.toLowerCase(Locale.ROOT);
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql
                    && "23505".equals(sql.getSQLState())
                    && contains(current.getMessage(), expected)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String value, String expected) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(expected);
    }
}
