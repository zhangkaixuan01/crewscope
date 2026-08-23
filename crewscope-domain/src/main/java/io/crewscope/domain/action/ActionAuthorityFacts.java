package io.crewscope.domain.action;

import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.review.ReviewDecision;
import io.crewscope.domain.review.ReviewDiffReference;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.SafetyEnforcementOverlay;
import java.util.Objects;

/** Current server-resolved objects used to build or revalidate an Action authority snapshot. */
public record ActionAuthorityFacts(
        ReviewRequest reviewRequest,
        ContextPackage contextPackage,
        ReviewDecision reviewDecision,
        ReviewDiffReference diff,
        ResponsibilityAssignment responsibility,
        ProviderBinding providerBinding,
        Connection connection,
        ConnectionGrant connectionGrant,
        PolicySnapshot policySnapshot,
        SafetyEnforcementOverlay safetyOverlay,
        CodingTargetSnapshot codingTarget,
        RepositoryBinding repositoryBinding) {

    public ActionAuthorityFacts {
        reviewRequest = Objects.requireNonNull(reviewRequest, "reviewRequest");
        contextPackage = Objects.requireNonNull(contextPackage, "contextPackage");
        reviewDecision = Objects.requireNonNull(reviewDecision, "reviewDecision");
        diff = Objects.requireNonNull(diff, "diff");
        responsibility = Objects.requireNonNull(responsibility, "responsibility");
        providerBinding = Objects.requireNonNull(providerBinding, "providerBinding");
        connection = Objects.requireNonNull(connection, "connection");
        connectionGrant = Objects.requireNonNull(connectionGrant, "connectionGrant");
        policySnapshot = Objects.requireNonNull(policySnapshot, "policySnapshot");
        safetyOverlay = Objects.requireNonNull(safetyOverlay, "safetyOverlay");
        codingTarget = Objects.requireNonNull(codingTarget, "codingTarget");
        repositoryBinding = Objects.requireNonNull(repositoryBinding, "repositoryBinding");
    }
}
