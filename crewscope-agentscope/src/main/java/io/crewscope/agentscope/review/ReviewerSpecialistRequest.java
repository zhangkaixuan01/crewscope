package io.crewscope.agentscope.review;

import io.agentscope.core.util.JsonUtils;
import io.crewscope.agentscope.template.TemplateAgentBuildRequest;
import io.crewscope.application.review.output.ReviewerStructuredOutputSpecs;
import io.crewscope.domain.agent.AgentTemplateHash;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.ReviewRequestStatus;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Complete trusted authority for one reviewer@1 AgentScope invocation. */
public record ReviewerSpecialistRequest(
        TemplateAgentBuildRequest agentBuild,
        ReviewRequest reviewRequest,
        ContextPackage contextPackage,
        long expectedRequestVersion,
        Principal reviewerAgent,
        UtcTimestamp observedAt) {

    public ReviewerSpecialistRequest {
        agentBuild = Objects.requireNonNull(agentBuild, "agentBuild");
        reviewRequest = Objects.requireNonNull(reviewRequest, "reviewRequest");
        contextPackage = Objects.requireNonNull(contextPackage, "contextPackage");
        reviewerAgent = Objects.requireNonNull(reviewerAgent, "reviewerAgent");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        AgentTemplateHash runtimeSchemaHash = AgentTemplateHash.sha256(
                JsonUtils.getJsonCodec().toJson(
                        ReviewerStructuredOutputSpecs.REVIEW_FINDING_LIST
                                .strictJsonSchema()
                                .orElseThrow()));
        if (!"reviewer".equals(agentBuild.definition()
                        .template().templateVersion().key().value())
                || agentBuild.definition().template().templateVersion().version() != 1
                || !agentBuild.definition().enabledToolNames().isEmpty()
                || agentBuild.definition().template().policy()
                        .structuredOutputSchemaHash().filter(runtimeSchemaHash::equals).isEmpty()) {
            throw new IllegalArgumentException(
                    "Reviewer Specialist requires reviewer@1, no Tools and the exact output schema");
        }
        if (!agentBuild.identity().agentPrincipalId().equals(reviewerAgent.id())
                || !contextPackage.reviewer().agentPrincipalId().equals(reviewerAgent.id())
                || !contextPackage.reviewer().agentProfileId()
                        .equals(agentBuild.identity().agentProfileId())
                || contextPackage.reviewer().agentProfileVersion()
                        != agentBuild.identity().agentProfileVersion()
                || !contextPackage.reviewer().templateVersion()
                        .equals(agentBuild.definition().template().templateVersion())
                || !contextPackage.reviewer().templateHash()
                        .equals(agentBuild.definition().template().contentHash())
                || !contextPackage.reviewer().configurationRevision()
                        .equals(agentBuild.definition().configuration().revision())
                || !contextPackage.reviewer().configurationHash()
                        .equals(agentBuild.definition().configuration().configurationHash())) {
            throw new IllegalArgumentException(
                    "Reviewer Agent, Profile, Template, Configuration and Context authority must match exactly");
        }
        reviewRequest.requireCurrent(contextPackage);
        if (reviewRequest.status() != ReviewRequestStatus.IN_PROGRESS
                || reviewRequest.version() != expectedRequestVersion) {
            throw new IllegalArgumentException(
                    "Reviewer invocation requires the exact IN_PROGRESS ReviewRequest ETag");
        }
    }
}
