package io.crewscope.agentscope.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.crewscope.agentscope.template.AgentTemplateRuntimeDefinition;
import io.crewscope.agentscope.template.TemplateAgentBuildRequest;
import io.crewscope.agentscope.template.TemplateAgentSessionIdentity;
import io.crewscope.application.review.ReviewFindingBatchRecorder;
import io.crewscope.application.review.ReviewFindingBatchResult;
import io.crewscope.application.review.ReviewRepairRequestSummary;
import io.crewscope.application.review.output.ReviewerStructuredOutputSpecs;
import io.crewscope.domain.agent.AgentConfigurationHash;
import io.crewscope.domain.agent.AgentConfigurationRevision;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplateHash;
import io.crewscope.domain.agent.AgentTemplatePolicy;
import io.crewscope.domain.agent.AgentTemplateVersion;
import io.crewscope.domain.coding.AcceptanceResult;
import io.crewscope.domain.coding.AcceptanceStatus;
import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.coding.CodingTargetSnapshotReference;
import io.crewscope.domain.coding.CommandEvidenceId;
import io.crewscope.domain.coding.CommandEvidenceReference;
import io.crewscope.domain.coding.CommandKind;
import io.crewscope.domain.coding.CommandTermination;
import io.crewscope.domain.coding.DiffArtifactId;
import io.crewscope.domain.coding.DiffArtifactReference;
import io.crewscope.domain.coding.DiffGeneration;
import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.coding.EvidenceSequence;
import io.crewscope.domain.coding.EvidenceSummary;
import io.crewscope.domain.coding.PatchArtifactReference;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.TestEvidenceId;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.review.ContextPackageId;
import io.crewscope.domain.review.ReviewCommandEvidenceReference;
import io.crewscope.domain.review.ReviewDiffHunk;
import io.crewscope.domain.review.ReviewDiffReference;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.review.ReviewSubject;
import io.crewscope.domain.review.ReviewSubjectId;
import io.crewscope.domain.review.ReviewTestEvidenceReference;
import io.crewscope.domain.review.ReviewerExecutionReference;
import io.crewscope.domain.review.ReviewerRelationship;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/** M5-I06 drives the production runtime through AgentScope's synthetic structured-output path. */
class ReviewerSpecialistRuntimeM5I06Test {

    @TempDir Path workspace;

    @Test
    void executesProductionSchemaAndPersistsOnlyDecodedCandidates() {
        Fixture fixture = new Fixture();
        ReviewFindingBatchRecorder recorder = mock(ReviewFindingBatchRecorder.class);
        ReviewFindingBatchResult recorded = new ReviewFindingBatchResult(
                List.of(), List.of(), List.of(), ReviewRepairRequestSummary.from(
                        fixture.request, fixture.context, List.of()));
        when(recorder.record(any(), any(), anyList(), anyLong(), any(), any()))
                .thenReturn(recorded);
        FixtureModel model = new FixtureModel();
        ReviewerAgentProvider provider = ignored -> HarnessAgent.builder()
                .name("crewscope-m5-i06-reviewer")
                .agentId("crewscope-m5-i06-reviewer")
                .sysPrompt("Return only ReviewFindingListV1 advisory findings. Never emit Gate decisions.")
                .model(model)
                .toolkit(new Toolkit())
                .stateStore(new InMemoryAgentStateStore())
                .workspace(workspace)
                .disableFilesystemTools()
                .disableShellTool()
                .disableSubagents()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .disableToolsConfig()
                .build();
        ReviewerSpecialistRuntime runtime =
                new ReviewerSpecialistRuntime(provider, recorder, Duration.ofSeconds(5));

        ReviewFindingBatchResult result = runtime.review(fixture.runtimeRequest()).block();

        assertEquals(recorded, result);
        assertEquals(1, model.calls.get());
        assertTrue(model.lastPrompt.contains(fixture.context.contextHash().toString()));
        assertTrue(model.lastPrompt.contains("untrusted evidence"));
        verify(recorder).record(
                fixture.request, fixture.context, List.of(), fixture.request.version(),
                fixture.reviewerAgent, Fixture.LATER);
    }

    @Test
    void rejectsAReviewerTemplateVersionOtherThanTheFrozenReviewerAtOne() {
        Fixture fixture = new Fixture();
        when(fixture.agentBuild.definition().template().templateVersion())
                .thenReturn(AgentTemplateVersion.of("reviewer", 2));

        assertThrows(IllegalArgumentException.class, fixture::runtimeRequest);
    }

    private static final class FixtureModel implements Model {
        private final AtomicInteger calls = new AtomicInteger();
        private String lastPrompt = "";

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.incrementAndGet();
            lastPrompt = messages.stream().map(Msg::getTextContent)
                    .reduce("", (left, right) -> left + '\n' + right);
            assertTrue(tools.stream().anyMatch(tool -> "generate_response".equals(tool.getName())));
            Map<String, Object> response = Map.of("schemaVersion", "1", "findings", List.of());
            Map<String, Object> input = Map.of("response", response);
            return Flux.just(ChatResponse.builder()
                    .content(List.of(ToolUseBlock.builder()
                            .id("review-output")
                            .name("generate_response")
                            .input(input)
                            .content(JsonUtils.getJsonCodec().toJson(input))
                            .build()))
                    .usage(new ChatUsage(20, 5, 0.01))
                    .build());
        }

        @Override public String getModelName() { return "m5-i06-fixture"; }
    }

    private static final class Fixture {
        private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-23T06:00:00Z");
        private static final UtcTimestamp LATER = UtcTimestamp.parse("2026-08-23T06:01:00Z");
        private static final String PATH = "src/main/java/io/crewscope/Greeting.java";
        private static final String PATCH = "+return name == null ? \"\" : name.strip();\n";
        private final WorkItemScope scope = new WorkItemScope(
                OrganizationId.generate(), TeamId.generate(), WorkspaceId.generate(),
                WorkProjectId.generate());
        private final TaskId taskId = TaskId.generate();
        private final TaskExecutionId executionId = TaskExecutionId.generate();
        private final Principal actor = principal(PrincipalType.USER, "Review owner", Optional.empty());
        private final Principal reviewerAgent = principal(
                PrincipalType.SPECIALIST_AGENT, "Reviewer", Optional.of(actor.id()));
        private final AgentProfileId profileId = AgentProfileId.generate();
        private final ReviewDiffReference diff;
        private final ReviewerExecutionReference reviewer;
        private final ContextPackage context;
        private final ReviewRequest request;
        private final TemplateAgentBuildRequest agentBuild;

        private Fixture() {
            CodingTargetSnapshotReference target = new CodingTargetSnapshotReference(
                    CodingTargetSnapshotId.generate(), 1, TaskFactHash.sha256("target"));
            diff = new ReviewDiffReference(
                    scope, taskId, executionId, 1,
                    new DiffArtifactReference(DiffArtifactId.generate(), TaskFactHash.sha256("diff")),
                    target, new RepositoryCommitId("a".repeat(40)),
                    new RepositoryCommitId("b".repeat(40)), DiffGeneration.first(),
                    RuntimeContentHash.sha256("manifest"),
                    new PatchArtifactReference(
                            ArtifactId.generate(), PATCH.getBytes(StandardCharsets.UTF_8).length,
                            RuntimeContentHash.sha256(PATCH)),
                    List.of(new DiffPath(PATH)));
            CommandEvidenceReference command = new CommandEvidenceReference(
                    CommandEvidenceId.generate(), EvidenceSequence.first(),
                    TaskFactHash.sha256("command"), Optional.empty());
            ReviewTestEvidenceReference test = new ReviewTestEvidenceReference(
                    scope, taskId, executionId, 1, target, TestEvidenceId.generate(),
                    TaskFactHash.sha256("test"), diff.generation(), diff.manifestHash(),
                    List.of(new ReviewCommandEvidenceReference(
                            command, CommandKind.TEST, CommandTermination.EXITED, Optional.of(0),
                            new EvidenceSummary("Tests passed"))),
                    List.of(new AcceptanceResult(
                            1, "Handle null", AcceptanceStatus.PASSED, List.of(command),
                            new EvidenceSummary("Passed"))));
            reviewer = new ReviewerExecutionReference(
                    scope, taskId, executionId, profileId, 1, reviewerAgent.id(),
                    Optional.of(TeamMemberId.generate()), Optional.of(TeamMemberId.generate()),
                    ReviewerRelationship.INDEPENDENT, AgentTemplateVersion.of("reviewer", 1),
                    AgentTemplateHash.sha256("template"), new AgentConfigurationRevision(1),
                    new AgentConfigurationHash(TaskFactHash.sha256("configuration").value()),
                    PolicySnapshotId.generate(), 1, TaskFactHash.sha256("policy"));
            ReviewSubject subject = ReviewSubject.codeChange(
                    ReviewSubjectId.generate(), scope, taskId, executionId, 1, diff, actor, NOW);
            context = ContextPackage.initial(
                    ContextPackageId.generate(), subject, diff, test,
                    List.of(ReviewDiffHunk.captured(PATH, 13, 13, PATCH)), reviewer, actor, NOW);
            ReviewRequest open = ReviewRequest.initial(ReviewRequestId.generate(), context, actor, NOW);
            request = open.start(context, open.version(), actor, LATER);
            agentBuild = buildRequest();
        }

        private ReviewerSpecialistRequest runtimeRequest() {
            return new ReviewerSpecialistRequest(
                    agentBuild, request, context, request.version(), reviewerAgent, LATER);
        }

        private TemplateAgentBuildRequest buildRequest() {
            TemplateAgentBuildRequest build = mock(TemplateAgentBuildRequest.class);
            AgentTemplateRuntimeDefinition definition = mock(AgentTemplateRuntimeDefinition.class);
            AgentTemplateDefinition template = mock(AgentTemplateDefinition.class);
            AgentConfigurationVersion configuration = mock(AgentConfigurationVersion.class);
            AgentTemplatePolicy policy = mock(AgentTemplatePolicy.class);
            TemplateAgentSessionIdentity identity = mock(TemplateAgentSessionIdentity.class);
            String schema = JsonUtils.getJsonCodec().toJson(
                    ReviewerStructuredOutputSpecs.REVIEW_FINDING_LIST
                            .strictJsonSchema().orElseThrow());
            when(build.definition()).thenReturn(definition);
            when(build.identity()).thenReturn(identity);
            when(definition.template()).thenReturn(template);
            when(definition.configuration()).thenReturn(configuration);
            when(definition.enabledToolNames()).thenReturn(Set.of());
            when(template.templateVersion()).thenReturn(AgentTemplateVersion.of("reviewer", 1));
            when(template.contentHash()).thenReturn(reviewer.templateHash());
            when(template.policy()).thenReturn(policy);
            when(configuration.revision()).thenReturn(reviewer.configurationRevision());
            when(configuration.configurationHash()).thenReturn(reviewer.configurationHash());
            when(policy.structuredOutputSchemaHash())
                    .thenReturn(Optional.of(AgentTemplateHash.sha256(schema)));
            when(identity.agentPrincipalId()).thenReturn(reviewerAgent.id());
            when(identity.agentProfileId()).thenReturn(profileId);
            when(identity.agentProfileVersion()).thenReturn(1L);
            when(identity.agentScopeKey()).thenReturn(AgentScopeSessionKey.forTaskExecution(
                    scope.organizationId(), reviewerAgent.id(), executionId,
                    io.crewscope.domain.conversation.AgentRuntimeSessionId.forTaskExecution(
                            executionId, Optional.empty(), profileId, "reviewer")));
            return build;
        }

        private Principal principal(
                PrincipalType type, String name, Optional<PrincipalId> owner) {
            return Principal.create(
                    PrincipalId.generate(), PrincipalScope.team(scope.organizationId(), scope.teamId()),
                    type, owner, name, Optional.empty(), PrincipalVisibility.TEAM, NOW);
        }
    }
}
