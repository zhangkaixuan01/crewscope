package io.crewscope.application.review;

import io.crewscope.application.coding.CodingArtifactContent;
import io.crewscope.application.coding.CodingArtifactContentPort;
import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.review.ReviewCommandEvidenceReference;
import io.crewscope.domain.review.ReviewDiffReference;
import io.crewscope.domain.review.ReviewTestEvidenceReference;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Builds a hash-closed Reviewer context from exact M4 Diff, Test and Command evidence. */
public final class ContextPackageBuilder {

    private final CodingArtifactContentPort artifacts;
    private final ReviewPatchHunkParser patchParser = new ReviewPatchHunkParser();

    public ContextPackageBuilder(CodingArtifactContentPort artifacts) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    }

    public ContextPackage build(BuildReviewContextPackageRequest request) {
        BuildReviewContextPackageRequest required = Objects.requireNonNull(request, "request");
        requireAuthority(required);
        String patch = readPatch(required);
        Set<DiffPath> changedPaths = Set.copyOf(
                required.diffArtifact().manifest().files().stream()
                        .map(file -> file.path())
                        .toList());
        var hunks = patchParser.parse(patch, changedPaths);
        ReviewTestEvidenceReference test = ReviewTestEvidenceReference.from(
                required.testEvidence(),
                required.commandEvidence().stream()
                        .map(ReviewCommandEvidenceReference::from)
                        .toList());
        return required.predecessor()
                .map(parent -> ContextPackage.successor(
                        required.contextPackageId(),
                        parent,
                        required.subject(),
                        ReviewDiffReference.from(required.diffArtifact()),
                        test,
                        hunks,
                        required.reviewer(),
                        required.actor(),
                        required.createdAt()))
                .orElseGet(() -> ContextPackage.initial(
                        required.contextPackageId(),
                        required.subject(),
                        ReviewDiffReference.from(required.diffArtifact()),
                        test,
                        hunks,
                        required.reviewer(),
                        required.actor(),
                        required.createdAt()));
    }

    private static void requireAuthority(BuildReviewContextPackageRequest request) {
        DiffArtifact diff = request.diffArtifact();
        TestEvidence test = request.testEvidence();
        boolean commandMismatch = request.commandEvidence().stream().anyMatch(command ->
                !command.scope().equals(diff.scope())
                        || !command.taskId().equals(diff.taskId())
                        || !command.taskExecutionId().equals(diff.taskExecutionId())
                        || command.attempt() != diff.attempt()
                        || !command.codingTarget().equals(diff.codingTarget()));
        boolean mismatch = !request.subject().diff().equals(ReviewDiffReference.from(diff))
                || !request.subject().scope().equals(diff.scope())
                || !request.subject().taskId().equals(diff.taskId())
                || !request.subject().taskExecutionId().equals(diff.taskExecutionId())
                || request.subject().attempt() != diff.attempt()
                || !diff.scope().equals(test.scope())
                || !diff.taskId().equals(test.taskId())
                || !diff.taskExecutionId().equals(test.taskExecutionId())
                || diff.attempt() != test.attempt()
                || !diff.codingTarget().equals(test.codingTarget())
                || !diff.manifest().generation().equals(test.diffGeneration())
                || !diff.manifest().contentHash().equals(test.diffManifestHash())
                || !test.commands().equals(request.commandEvidence().stream()
                        .map(CommandEvidence::reference)
                        .toList())
                || commandMismatch
                || !request.artifactAccess().organizationId().equals(diff.scope().organizationId())
                || !request.artifactAccess().principalId().equals(request.actor().id())
                || !request.artifactAccess().authorizedTeamIds().contains(diff.scope().teamId())
                || !request.artifactAccess().authorizedWorkspaceIds().contains(diff.scope().workspaceId());
        if (mismatch) {
            throw new DomainValidationException(
                    "contextPackage", "M4 Artifact facts or access scope do not match Review authority");
        }
    }

    private String readPatch(BuildReviewContextPackageRequest request) {
        DiffArtifact diff = request.diffArtifact();
        if (diff.patchArtifact().sizeBytes() > ContextPackage.MAX_PATCH_BYTES) {
            throw new DomainValidationException(
                    "contextPackage.patch", "Patch exceeds the bounded Reviewer context budget");
        }
        try (CodingArtifactContent content = artifacts.readPatch(
                diff, request.artifactAccess(), Optional.empty())) {
            if (content.partial()
                    || content.totalSize() != diff.patchArtifact().sizeBytes()
                    || !content.contentHash().equals(diff.patchArtifact().patchSha256())) {
                throw new DomainValidationException(
                        "contextPackage.patch", "Artifact bytes do not match the exact Diff authority");
            }
            byte[] bytes = content.stream().readAllBytes();
            if (bytes.length != content.totalSize()
                    || bytes.length > ContextPackage.MAX_PATCH_BYTES) {
                throw new DomainValidationException(
                        "contextPackage.patch", "Artifact content length is outside the exact budget");
            }
            String patch = new String(bytes, StandardCharsets.UTF_8);
            if (!java.util.Arrays.equals(bytes, patch.getBytes(StandardCharsets.UTF_8))) {
                throw new DomainValidationException(
                        "contextPackage.patch", "Patch must contain canonical UTF-8 text");
            }
            if (!io.crewscope.domain.task.RuntimeContentHash.sha256(patch)
                    .equals(diff.patchArtifact().patchSha256())) {
                throw new DomainValidationException(
                        "contextPackage.patch", "Patch bytes do not match the committed SHA-256");
            }
            return patch;
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read the governed Diff Artifact", failure);
        }
    }
}
