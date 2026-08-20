package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.coding.RepositoryBindingApplicationService;
import io.crewscope.application.coding.RepositoryBindingPreflightError;
import io.crewscope.application.coding.RepositoryBindingPreflightException;
import io.crewscope.application.coding.RepositoryBindingPreflightResult;
import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Proves the M4-A01 route, authorization input, concurrency and safe response contracts. */
class RepositoryBindingControllerM4A01Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-19T14:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal actor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Owner",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);
    private final TeamInitialization initialization = TeamInitialization.create(actor, "Team", NOW);
    private final WorkProject project = WorkProject.create(
            WorkProjectId.generate(),
            new WorkProjectKey("CODE"),
            "Code",
            initialization.team(),
            initialization.defaultWorkspace(),
            actor,
            NOW);
    private final RepositoryBinding binding = RepositoryBinding.registerLocalManaged(
            RepositoryBindingId.generate(),
            project,
            new RepositoryKey("crewscope"),
            new RepositoryBranchName("main"),
            actor,
            NOW);

    private RepositoryBindingApplicationService service;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(RepositoryBindingApplicationService.class);
        TeamRequestIdentityResolver resolver =
                (authentication, organization, correlationId) ->
                        Mono.just(new TeamAccessContext(actor, false));
        client = WebTestClient.bindToController(new RepositoryBindingController(service, resolver))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void createsUsingTheSharedIdempotentReceiptContract() {
        CommandReceipt receipt = receipt(0);
        when(service.create(any(), any(), any(), any()))
                .thenReturn(CommandExecution.completed(binding, receipt));

        client.post()
                .uri(base())
                .header(ApiHeaders.IDEMPOTENCY_KEY, "register-repository-http-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"repositoryKey\":\"crewscope\",\"defaultBranch\":\"main\"}")
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody()
                .jsonPath("$.domainEventId")
                .isEqualTo(receipt.domainEventId().toString())
                .jsonPath("$.committedVersion")
                .isEqualTo(0);
    }

    @Test
    void listsAndReturnsDetailWithoutAnyHostPath() {
        when(service.list(any(), any(), any(), any())).thenReturn(List.of(binding));
        when(service.get(any(), any(), any(), any(), any())).thenReturn(binding);

        client.get()
                .uri(base())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.items[0].repositoryKey")
                .isEqualTo("crewscope")
                .jsonPath("$.items[0].canonicalPath")
                .doesNotExist();

        client.get()
                .uri(base() + "/" + binding.id())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(ApiHeaders.ETAG, "\"0\"")
                .expectBody()
                .jsonPath("$.workspaceId")
                .isEqualTo(initialization.defaultWorkspace().id().toString())
                .jsonPath("$.path")
                .doesNotExist();
    }

    @Test
    void preflightsDraftAndExistingBindingsUsingPublicSafeFacts() {
        RepositoryBindingPreflightResult result = new RepositoryBindingPreflightResult(
                binding.repositoryKey(),
                binding.defaultBranch(),
                new RepositoryCommitId("0123456789abcdef0123456789abcdef01234567"));
        when(service.preflightDraft(any(), any(), any(), any(), any(), any())).thenReturn(result);
        when(service.preflightExisting(any(), any(), any(), any(), any())).thenReturn(result);

        client.post()
                .uri(base() + "/preflight")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"repositoryKey\":\"crewscope\",\"defaultBranch\":\"main\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.ready")
                .isEqualTo(true)
                .jsonPath("$.baselineCommit")
                .isEqualTo(result.baselineCommit().value())
                .jsonPath("$.repositoryPath")
                .doesNotExist();

        client.post()
                .uri(base() + "/" + binding.id() + "/preflight")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.repositoryKey")
                .isEqualTo("crewscope");
    }

    @Test
    void disablesAndActivatesWithStrongVersionPreconditions() {
        RepositoryBinding disabled = binding.disable(0, actor, NOW);
        RepositoryBinding activated = disabled.activate(1, actor, NOW);
        when(service.disable(any(), any(), any(), eq(binding.id()), eq(0L)))
                .thenReturn(CommandExecution.completed(disabled, receipt(1)));
        when(service.activate(any(), any(), any(), eq(binding.id()), eq(1L)))
                .thenReturn(CommandExecution.completed(activated, receipt(2)));

        client.post()
                .uri(base() + "/" + binding.id() + "/disable")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "disable-repository-http-1")
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody()
                .jsonPath("$.committedVersion")
                .isEqualTo(1);

        client.post()
                .uri(base() + "/" + binding.id() + "/activate")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "activate-repository-http-1")
                .header(ApiHeaders.IF_MATCH, "\"1\"")
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody()
                .jsonPath("$.committedVersion")
                .isEqualTo(2);
    }

    @Test
    void mapsVersionConflictAndPreflightFailuresToStableSafeEnvelopes() {
        when(service.disable(any(), any(), any(), any(), eq(0L)))
                .thenThrow(new OptimisticLockConflictException(
                        "RepositoryBinding", binding.id(), 0, 2));
        when(service.preflightExisting(any(), any(), any(), any(), any()))
                .thenThrow(new RepositoryBindingPreflightException(
                        RepositoryBindingPreflightError.REPOSITORY_NOT_FOUND,
                        "Managed repository Preflight failed"));

        client.post()
                .uri(base() + "/" + binding.id() + "/disable")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "disable-repository-http-stale")
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.currentVersion")
                .isEqualTo(2);

        client.post()
                .uri(base() + "/" + binding.id() + "/preflight")
                .exchange()
                .expectStatus()
                .isEqualTo(422)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("repository_preflight_repository_not_found")
                .jsonPath("$.details.reason")
                .isEqualTo("REPOSITORY_NOT_FOUND");

        doThrow(new RepositoryBindingPreflightException(
                        RepositoryBindingPreflightError.COMMAND_FAILED,
                        "Managed repository Preflight command failed"))
                .when(service)
                .preflightExisting(any(), any(), any(), any(), any());
        client.post()
                .uri(base() + "/" + binding.id() + "/preflight")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("repository_preflight_command_failed")
                .jsonPath("$.retryable")
                .isEqualTo(true);
    }

    @Test
    void rejectsInvalidIdentifiersKeysAndMissingConcurrencyHeaders() {
        client.post()
                .uri(base())
                .header(ApiHeaders.IDEMPOTENCY_KEY, "invalid-repository-key-http")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"repositoryKey\":\"../repo\",\"defaultBranch\":\"main\"}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("invalid_request");

        client.post()
                .uri(base() + "/" + binding.id() + "/disable")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "missing-if-match-http")
                .exchange()
                .expectStatus()
                .isEqualTo(428)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("precondition_required");

        client.get()
                .uri(base() + "/not-a-uuid")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.details.field")
                .isEqualTo("bindingId");
    }

    private String base() {
        return "/api/v1/organizations/"
                + organizationId
                + "/teams/"
                + initialization.team().id()
                + "/work-projects/"
                + project.id()
                + "/repository-bindings";
    }

    private static CommandReceipt receipt(long version) {
        return new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), version, UUID.randomUUID());
    }
}
