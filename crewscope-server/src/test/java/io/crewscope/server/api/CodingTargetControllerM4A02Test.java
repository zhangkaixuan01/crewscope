package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.coding.CodingTargetSelectionService;
import io.crewscope.application.coding.RepositoryBindingPreflightResult;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.coding.BuildCommand;
import io.crewscope.domain.coding.BuildProfile;
import io.crewscope.domain.coding.BuildTool;
import io.crewscope.domain.coding.CommandCatalog;
import io.crewscope.domain.coding.CommandKind;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.coding.SandboxImageReference;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** HTTP contract for member-visible M4-A02 BuildProfile options and Ref Preflight. */
class CodingTargetControllerM4A02Test {

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final WorkProjectId projectId = WorkProjectId.generate();
    private final WorkItemId workItemId = WorkItemId.generate();
    private final Principal actor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.team(organizationId, teamId),
            PrincipalType.USER,
            Optional.empty(),
            "Member",
            Optional.empty(),
            PrincipalVisibility.TEAM,
            UtcTimestamp.parse("2026-08-19T08:00:00Z"));

    private CodingTargetSelectionService service;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(CodingTargetSelectionService.class);
        TeamRequestIdentityResolver resolver = (authentication, organization, correlationId) ->
                Mono.just(new TeamAccessContext(actor, false));
        client = WebTestClient.bindToController(new CodingTargetController(service, resolver))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void exposesPublicProfileFactsWithoutImageOrTypedArgv() {
        BuildProfile profile = BuildProfile.define(
                "maven-java-17",
                1,
                BuildTool.MAVEN,
                17,
                new SandboxImageReference("maven@sha256:" + "f".repeat(64)),
                CommandCatalog.of(
                        CommandKind.TEST,
                        new BuildCommand(
                                "coding.maven.test",
                                List.of("mvn", "test"),
                                ".",
                                60,
                                900)));
        when(service.listBuildProfiles(any(), any(), any(), any(), any()))
                .thenReturn(List.of(profile));

        client.get()
                .uri(root() + "/build-profiles")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.items[0].key").isEqualTo("maven-java-17")
                .jsonPath("$.items[0].version").isEqualTo(1)
                .jsonPath("$.items[0].profileHash").isEqualTo(profile.profileHash().value())
                .jsonPath("$.items[0].buildTool").isEqualTo("MAVEN")
                .jsonPath("$.items[0].javaRelease").isEqualTo(17)
                .jsonPath("$.items[0].commandKinds[0]").isEqualTo("TEST")
                .jsonPath("$.items[0].sandboxImage").doesNotExist()
                .jsonPath("$.items[0].commandCatalog").doesNotExist();
    }

    @Test
    void preflightsAnExplicitMemberSelectedRef() {
        RepositoryBindingId bindingId = RepositoryBindingId.generate();
        RepositoryBindingPreflightResult result = new RepositoryBindingPreflightResult(
                new RepositoryKey("crewscope-java"),
                new RepositoryBranchName("feature/coding"),
                new RepositoryCommitId("1".repeat(40)));
        when(service.preflight(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(result);

        client.post()
                .uri(root() + "/preflight")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "repositoryBindingId":"%s",
                          "baselineRef":"feature/coding"
                        }
                        """.formatted(bindingId))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.ready").isEqualTo(true)
                .jsonPath("$.repositoryKey").isEqualTo("crewscope-java")
                .jsonPath("$.baselineRef").isEqualTo("feature/coding")
                .jsonPath("$.baselineCommit").isEqualTo("1".repeat(40));

        ArgumentCaptor<RepositoryBranchName> ref =
                ArgumentCaptor.forClass(RepositoryBranchName.class);
        verify(service).preflight(
                any(),
                any(),
                any(),
                any(),
                any(),
                org.mockito.ArgumentMatchers.eq(bindingId),
                ref.capture());
        org.junit.jupiter.api.Assertions.assertEquals("feature/coding", ref.getValue().value());
    }

    @Test
    void rejectsInvalidRoutesAndRefsBeforeCallingTheService() {
        client.get()
                .uri(root().replace(projectId.toString(), "not-a-project") + "/build-profiles")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_request");

        client.post()
                .uri(root() + "/preflight")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "repositoryBindingId":"%s",
                          "baselineRef":"../outside"
                        }
                        """.formatted(UUID.randomUUID()))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.code").isEqualTo("invalid_value");
    }

    private String root() {
        return "/api/v1/organizations/" + organizationId
                + "/teams/" + teamId
                + "/work-projects/" + projectId
                + "/work-items/" + workItemId
                + "/coding-target";
    }
}
