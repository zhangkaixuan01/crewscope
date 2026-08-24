package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.model.ModelConnectionApplicationService;
import io.crewscope.application.model.ModelConnectionCredentialException;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.model.ModelAdapterKey;
import io.crewscope.domain.model.ModelBillingSubject;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.model.ModelCredentialBinding;
import io.crewscope.domain.model.ModelCredentialSubject;
import io.crewscope.domain.model.ModelCredentialVersion;
import io.crewscope.domain.model.ModelDataPolicy;
import io.crewscope.domain.model.ModelEndpoint;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** M5-A01 route, ETag, receipt and sensitive-field whitelist tests. */
class ModelManagementControllerM5A01Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-24T10:00:00Z");

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
    private final ModelProviderDefinition provider = ModelProviderDefinition.publish(
            new ModelProviderKey("deepseek"),
            "DeepSeek",
            new ModelAdapterKey("deepseek-internal"),
            new ModelEndpoint("https://secret-endpoint.example/v1"),
            java.util.Set.of(new ModelRegion("global")),
            ModelDataPolicy.noRetention(),
            actor.id(),
            NOW);
    private final ModelConnection connection = ModelConnection.open(
            provider,
            ModelConnectionId.generate(),
            ModelConnectionOwner.user(actor),
            provider.defaultEndpoint(),
            new ModelRegion("global"),
            new ModelCredentialBinding(
                    CredentialId.generate(),
                    ModelCredentialSubject.principal(organizationId, actor.id()),
                    new ModelCredentialVersion(0)),
            ModelBillingSubject.principal(organizationId, actor.id()),
            actor.id(),
            NOW);

    private ModelConnectionApplicationService service;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(ModelConnectionApplicationService.class);
        TeamRequestIdentityResolver resolver = (authentication, organization, correlationId) ->
                Mono.just(new TeamAccessContext(actor, false));
        client = WebTestClient.bindToController(
                        new ModelCatalogController(service, resolver),
                        new ModelConnectionController(service, resolver))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void listsProviderMetadataWithoutEndpointOrAdapterImplementation() {
        when(service.listProviders(any(), any(), any(Integer.class), any(Integer.class)))
                .thenReturn(List.of(provider));

        client.get()
                .uri("/api/v1/organizations/" + organizationId + "/model-providers")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.items[0].key").isEqualTo("deepseek")
                .jsonPath("$.items[0].availableRegions[0]").isEqualTo("global")
                .jsonPath("$.items[0].defaultEndpoint").doesNotExist()
                .jsonPath("$.items[0].adapterKey").doesNotExist();
    }

    @Test
    void returnsConnectionDetailWithStrongEtagAndNoCredentialOrEndpointFields() {
        when(service.getConnection(any(), any(), any())).thenReturn(connection);

        client.get()
                .uri(base() + "/" + connection.id())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(ApiHeaders.ETAG, "\"0\"")
                .expectBody()
                .jsonPath("$.credentialVersion").isEqualTo(0)
                .jsonPath("$.healthStatus").isEqualTo("UNKNOWN")
                .jsonPath("$.endpoint").doesNotExist()
                .jsonPath("$.credentialId").doesNotExist()
                .jsonPath("$.apiKey").doesNotExist()
                .jsonPath("$.metadata").doesNotExist();
    }

    @Test
    void createsAndRotatesUsingReceiptsWithoutEchoingApiKeys() {
        CommandReceipt created = receipt(0);
        CommandReceipt rotated = receipt(1);
        when(service.create(any(), any(), any()))
                .thenReturn(CommandExecution.completed(connection, created));
        when(service.rotate(any(), any(), any(Long.class), any(), any()))
                .thenReturn(CommandExecution.completed(connection, rotated));

        client.post()
                .uri(base())
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m5-a01-create-http")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"providerKey":"deepseek","ownerType":"USER","region":"global",
                         "apiKey":"never-echo-this"}
                        """)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.domainEventId").isEqualTo(created.domainEventId().toString())
                .jsonPath("$.apiKey").doesNotExist();

        client.post()
                .uri(base() + "/" + connection.id() + "/rotate")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m5-a01-rotate-http")
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"credentialVersion\":0,\"apiKey\":\"new-never-echo\"}")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.committedVersion").isEqualTo(1)
                .jsonPath("$.apiKey").doesNotExist();
    }

    @Test
    void requiresIdempotencyAndStrongVersionHeadersForMutations() {
        client.post()
                .uri(base() + "/" + connection.id() + "/suspend")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"credentialVersion\":0}")
                .exchange()
                .expectStatus().isEqualTo(428);

        client.post()
                .uri(base() + "/" + connection.id() + "/suspend")
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"credentialVersion\":0}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void mapsCredentialFailuresToStableSafeErrors() {
        when(service.getConnection(any(), any(), any()))
                .thenThrow(new ModelConnectionCredentialException(
                        ModelConnectionCredentialException.Error.CREDENTIAL_UNAVAILABLE,
                        "Provider credential is unavailable"));

        client.get()
                .uri(base() + "/" + connection.id())
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("model_connection_credential_unavailable")
                .jsonPath("$.details.reason").isEqualTo("CREDENTIAL_UNAVAILABLE")
                .jsonPath("$.details.endpoint").doesNotExist();
    }

    private String base() {
        return "/api/v1/organizations/" + organizationId + "/model-connections";
    }

    private static CommandReceipt receipt(long version) {
        return new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), version, UUID.randomUUID());
    }
}
