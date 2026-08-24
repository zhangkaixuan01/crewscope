package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.credential.CredentialStatus;
import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.github.GitHubAuthenticationType;
import io.crewscope.application.github.GitHubAuthorizationHealthView;
import io.crewscope.application.github.GitHubConnectionApplicationService;
import io.crewscope.application.github.GitHubConnectionView;
import io.crewscope.application.github.GitHubHash;
import io.crewscope.application.github.GitHubProviderErrorCode;
import io.crewscope.application.github.GitHubProviderException;
import io.crewscope.application.github.GitHubRemotePreflightView;
import io.crewscope.application.github.GitHubRepositoryView;
import io.crewscope.application.github.GitHubRepositoryVisibility;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.provider.ConnectionStatus;
import io.crewscope.domain.provider.ProviderExecutionIdentity;
import io.crewscope.domain.provider.ProviderOwnerType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** M5-A06 route, ETag, trusted-coordinate and sensitive-field whitelist tests. */
class GitHubConnectionControllerM5A06Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-24T13:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal actor = Principal.create(
            io.crewscope.domain.shared.id.PrincipalId.generate(),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Owner",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);
    private final String connectionId = java.util.UUID.randomUUID().toString();
    private final GitHubConnectionView connection = new GitHubConnectionView(
            connectionId,
            ProviderOwnerType.USER,
            Optional.empty(),
            GitHubAuthenticationType.OAUTH_USER,
            Optional.of(ProviderExecutionIdentity.DELEGATED_USER),
            Optional.of("crewscope-user"),
            ConnectionStatus.ACTIVE,
            0,
            List.of("crewscope/repository-a"),
            Optional.of(CredentialStatus.ACTIVE),
            Optional.empty(),
            Optional.of(NOW),
            NOW,
            NOW);
    private GitHubConnectionApplicationService service;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(GitHubConnectionApplicationService.class);
        TeamRequestIdentityResolver resolver = (authentication, organization, correlationId) ->
                Mono.just(new TeamAccessContext(actor, false));
        client = WebTestClient.bindToController(new GitHubConnectionController(service, resolver))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void createsConnectionWithoutEchoingTokenCredentialOrExternalAccountId() {
        CommandReceipt receipt = new CommandReceipt(
                java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(),
                0,
                java.util.UUID.randomUUID());
        when(service.create(any(), any(), any(), any()))
                .thenReturn(CommandExecution.completed(connection, receipt));

        client.post()
                .uri(base())
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m5-a06-create")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"authenticationType":"OAUTH_USER","credentialSubjectType":"PRINCIPAL",
                         "externalAccountId":"2718",
                         "repositoryAllowlist":["crewscope/repository-a"],
                         "accessToken":"never-echo-this-token"}
                        """)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.domainEventId").isEqualTo(receipt.domainEventId().toString())
                .jsonPath("$.externalAccountId").doesNotExist()
                .jsonPath("$.accessToken").doesNotExist()
                .jsonPath("$.credentialId").doesNotExist()
                .jsonPath("$.grantId").doesNotExist()
                .jsonPath("$.providerEndpoint").doesNotExist();

        when(service.get(any(), any(), any())).thenReturn(connection);
        client.get()
                .uri(base() + "/" + connectionId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.externalAccountLogin").isEqualTo("crewscope-user")
                .jsonPath("$.externalAccountId").doesNotExist()
                .jsonPath("$.credentialId").doesNotExist();
    }

    @Test
    void repositoryPreflightRequiresStrongVersionAndBindingCoordinates() {
        String bindingId = java.util.UUID.randomUUID().toString();
        GitHubRemotePreflightView result = new GitHubRemotePreflightView(
                0,
                "12345",
                "crewscope/repository-a",
                new RepositoryBranchName("main"),
                GitHubHash.sha256("permissions"));
        when(service.preflightRepository(
                        any(), any(), any(), anyLong(), any(), anyString(), any()))
                .thenReturn(result);

        client.post()
                .uri(base() + "/" + connectionId
                        + "/repositories/12345/preflight?bindingId=" + bindingId)
                .exchange()
                .expectStatus().isEqualTo(428);

        client.post()
                .uri(base() + "/" + connectionId
                        + "/repositories/12345/preflight?bindingId=" + bindingId)
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.externalRepositoryId").isEqualTo("12345")
                .jsonPath("$.defaultBranch").isEqualTo("main")
                .jsonPath("$.grantId").doesNotExist()
                .jsonPath("$.grantVersion").doesNotExist();
    }

    @Test
    void catalogAndHealthExposeOnlyStableSafeFacts() {
        GitHubRepositoryView repository = new GitHubRepositoryView(
                "12345",
                "crewscope/repository-a",
                new RepositoryBranchName("main"),
                GitHubRepositoryVisibility.PRIVATE,
                NOW,
                UtcTimestamp.parse("2026-08-24T13:05:00Z"));
        when(service.listCatalog(any(), any(), any())).thenReturn(List.of(repository));
        when(service.health(any(), any(), any())).thenReturn(new GitHubAuthorizationHealthView(
                "HEALTHY", true, true, true, true, 1, "CONFIGURED", Optional.empty()));

        client.get()
                .uri(base() + "/" + connectionId + "/repositories")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].fullName").isEqualTo("crewscope/repository-a")
                .jsonPath("$.items[0].remoteUrl").doesNotExist()
                .jsonPath("$.items[0].permissionsHash").doesNotExist();

        client.get()
                .uri(base() + "/" + connectionId + "/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.authorizationStatus").isEqualTo("HEALTHY")
                .jsonPath("$.webhookStatus").isEqualTo("CONFIGURED")
                .jsonPath("$.token").doesNotExist();
    }

    @Test
    void mapsRateLimitWithoutLeakingProviderPayload() {
        when(service.get(any(), any(), any())).thenThrow(new GitHubProviderException(
                GitHubProviderErrorCode.RATE_LIMITED,
                "GitHub request is rate limited"));

        client.get()
                .uri(base() + "/" + connectionId)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.code").isEqualTo("github_rate_limited")
                .jsonPath("$.retryable").isEqualTo(true)
                .jsonPath("$.details.reason").isEqualTo("RATE_LIMITED")
                .jsonPath("$.details.body").doesNotExist()
                .jsonPath("$.details.endpoint").doesNotExist();
    }

    private String base() {
        return "/api/v1/organizations/" + organizationId + "/github-connections";
    }
}
