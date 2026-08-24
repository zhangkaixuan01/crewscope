package io.crewscope.server.config.application;

import io.agentscope.core.tool.Toolkit;
import io.crewscope.agentscope.review.ReviewerSpecialistRequest;
import io.crewscope.agentscope.review.ReviewerSpecialistRuntime;
import io.crewscope.agentscope.template.AgentTemplateRuntimeAssembler;
import io.crewscope.agentscope.template.TemplateAgentBuildRequest;
import io.crewscope.agentscope.template.TemplateAgentSessionIdentity;
import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.agent.AgentTemplateRepository;
import io.crewscope.application.review.ReviewerExecutionCommand;
import io.crewscope.application.review.ReviewerExecutionPort;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.workspace.AgentProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Resolves the exact persisted Reviewer graph before invoking AgentScope. */
final class AgentScopeReviewerExecutionAdapter implements ReviewerExecutionPort {

    private final AgentProfileRepository profiles;
    private final AgentTemplateRepository templates;
    private final AgentConfigurationRepository configurations;
    private final AgentTemplateRuntimeAssembler assembler;
    private final ReviewerSpecialistRuntime runtime;

    AgentScopeReviewerExecutionAdapter(
            AgentProfileRepository profiles,
            AgentTemplateRepository templates,
            AgentConfigurationRepository configurations,
            AgentTemplateRuntimeAssembler assembler,
            ReviewerSpecialistRuntime runtime) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.templates = Objects.requireNonNull(templates, "templates");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public CompletionStage<List<io.crewscope.domain.review.ReviewFindingCandidate>> execute(
            ReviewerExecutionCommand command) {
        ReviewerExecutionCommand required = Objects.requireNonNull(command, "command");
        var organizationId = required.reviewRequest().scope().organizationId();
        AgentProfile profile = profiles.findById(
                        organizationId, required.reviewRequest().reviewer().agentProfileId())
                .filter(value -> value.version()
                        == required.reviewRequest().reviewer().agentProfileVersion())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "AgentProfile", required.reviewRequest().reviewer().agentProfileId()));
        var configuration = configurations.findByRevision(
                        organizationId,
                        profile.id(),
                        required.reviewRequest().reviewer().configurationRevision())
                .filter(value -> value.configurationHash().equals(
                        required.reviewRequest().reviewer().configurationHash()))
                .orElseThrow(() -> new DomainValidationException(
                        "reviewRequest.reviewerConfiguration",
                        "exact Reviewer configuration is unavailable"));
        AgentTemplateDefinition template = exactTemplate(
                profile, configuration.templateVersion(), configuration.templateContentHash());
        var resolved = required.policySnapshot().agentExecutionConfiguration()
                .orElseThrow(() -> new DomainValidationException(
                        "reviewRequest.reviewerPolicySnapshotId", "must use Schema v2"));
        var definition = assembler.assemble(
                profile, template, configuration, resolved,
                required.reviewerAgent().id(), required.correlationId());
        TemplateAgentBuildRequest build = new TemplateAgentBuildRequest(
                definition,
                TemplateAgentSessionIdentity.task(required.runtimeSession()),
                new Toolkit());
        return runtime.analyze(new ReviewerSpecialistRequest(
                        build,
                        required.reviewRequest(),
                        required.contextPackage(),
                        required.reviewRequest().version(),
                        required.reviewerAgent(),
                        required.observedAt()))
                .toFuture();
    }

    private AgentTemplateDefinition exactTemplate(
            AgentProfile profile,
            io.crewscope.domain.agent.AgentTemplateVersion version,
            io.crewscope.domain.agent.AgentTemplateHash contentHash) {
        List<AgentTemplateDefinition> matches = new ArrayList<>();
        profile.scope().teamId().flatMap(teamId -> templates.findByVersion(
                        AgentTemplatePublisherScope.team(
                                profile.scope().organizationId(), teamId), version))
                .ifPresent(matches::add);
        templates.findByVersion(
                        AgentTemplatePublisherScope.organization(
                                profile.scope().organizationId()), version)
                .ifPresent(matches::add);
        matches.removeIf(value -> !value.contentHash().equals(contentHash));
        if (matches.size() != 1) {
            throw new DomainValidationException(
                    "reviewRequest.reviewerTemplate", "exact Reviewer template is unavailable");
        }
        return matches.get(0);
    }
}
