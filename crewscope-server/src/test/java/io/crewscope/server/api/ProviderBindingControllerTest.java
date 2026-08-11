package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.provider.BuiltInProviderRegistration;
import io.crewscope.application.provider.ProviderBindingCandidate;
import io.crewscope.application.provider.ProviderBindingLookup;
import io.crewscope.application.provider.ProviderBindingQueryService;
import io.crewscope.application.provider.ProviderBindingResolution;
import io.crewscope.application.provider.ProviderBindingResolutionLevel;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderBindingTarget;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderConnectionRequirement;
import io.crewscope.domain.provider.ProviderDefinition;
import io.crewscope.domain.provider.ProviderImplementation;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Covers the read-only NativeWorkItem Binding API and its explicit resolution verdicts. */
class ProviderBindingControllerTest {

  private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-11T10:00:00Z");

  private final OrganizationId organizationId = OrganizationId.generate();
  private final Principal owner =
      Principal.create(
          PrincipalId.generate(),
          PrincipalScope.organization(organizationId),
          PrincipalType.USER,
          Optional.empty(),
          "Owner",
          Optional.empty(),
          PrincipalVisibility.ORGANIZATION,
          NOW);
  private final TeamInitialization team = TeamInitialization.create(owner, "Platform", NOW);
  private final BuiltInProviderRegistration registration = registration();
  private final ProviderBindingCandidate candidate = candidate();

  private ProviderBindingQueryService service;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    service = mock(ProviderBindingQueryService.class);
    TeamRequestIdentityResolver resolver =
        (authentication, organization, correlationId) ->
            Mono.just(new TeamAccessContext(owner, false));
    client =
        WebTestClient.bindToController(new ProviderBindingController(service, resolver))
            .controllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  void returnsTheResolvedConnectionlessBindingAndCapabilityEnvelope() {
    when(service.resolveDefault(any(), eq(organizationId), eq(team.team().id())))
        .thenReturn(
            new ProviderBindingLookup(
                registration,
                ProviderBindingResolution.resolved(
                    ProviderBindingResolutionLevel.WORKSPACE, candidate)));

    client
        .get()
        .uri(resource())
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectBody()
        .jsonPath("$.providerType")
        .isEqualTo("WORK_ITEM")
        .jsonPath("$.status")
        .isEqualTo("RESOLVED")
        .jsonPath("$.level")
        .isEqualTo("WORKSPACE")
        .jsonPath("$.binding.id")
        .isEqualTo(candidate.binding().id().toString())
        .jsonPath("$.binding.connectionless")
        .isEqualTo(true)
        .jsonPath("$.binding.defaultUsage")
        .isEqualTo(true)
        .jsonPath("$.binding.effectiveResources[0]")
        .isEqualTo("workspace:" + team.defaultWorkspace().id());
  }

  @Test
  void returnsNotFoundWithoutInventingABinding() {
    when(service.resolveDefault(any(), eq(organizationId), eq(team.team().id())))
        .thenReturn(
            new ProviderBindingLookup(
                registration,
                ProviderBindingResolution.notFound(ProviderBindingResolutionLevel.NONE)));

    client
        .get()
        .uri(resource())
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("NOT_FOUND")
        .jsonPath("$.level")
        .isEqualTo("NONE")
        .jsonPath("$.binding")
        .doesNotExist()
        .jsonPath("$.ambiguousBindingIds")
        .isArray();
  }

  @Test
  void returnsStableSortedIdsForAnAmbiguousBindingSet() {
    ProviderBindingId high = new ProviderBindingId(java.util.UUID.fromString(
        "ffffffff-ffff-ffff-ffff-ffffffffffff"));
    ProviderBindingId low = new ProviderBindingId(java.util.UUID.fromString(
        "00000000-0000-0000-0000-000000000001"));
    when(service.resolveDefault(any(), eq(organizationId), eq(team.team().id())))
        .thenReturn(
            new ProviderBindingLookup(
                registration,
                ProviderBindingResolution.ambiguous(
                    ProviderBindingResolutionLevel.WORKSPACE, List.of(high, low))));

    client
        .get()
        .uri(resource())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("AMBIGUOUS")
        .jsonPath("$.ambiguousBindingIds[0]")
        .isEqualTo(low.toString())
        .jsonPath("$.ambiguousBindingIds[1]")
        .isEqualTo(high.toString());
  }

  @Test
  void rejectsInvalidRouteIdentifiersAsClientErrors() {
    client
        .get()
        .uri("/api/v1/organizations/not-a-uuid/teams/" + team.team().id() + "/provider-bindings")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_request")
        .jsonPath("$.details.field")
        .isEqualTo("organizationId");

    client
        .get()
        .uri("/api/v1/organizations/" + organizationId + "/teams/not-a-uuid/provider-bindings")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.details.field")
        .isEqualTo("teamId");
  }

  private ProviderBindingCandidate candidate() {
    ProviderDefinition definition =
        ProviderDefinition.create(
            registration.definitionId(organizationId),
            organizationId,
            registration.definitionKey(),
            registration.type(),
            registration.interfaceVersion(),
            registration.displayName(),
            registration.capabilities(),
            owner,
            NOW);
    ProviderImplementation implementation =
        ProviderImplementation.create(
            registration.implementationId(organizationId),
            definition,
            registration.implementationKey(),
            registration.implementationVersion(),
            registration.capabilities(),
            ProviderConnectionRequirement.NONE,
            Optional.empty(),
            owner,
            NOW);
    ProviderBinding binding =
        ProviderBinding.bind(
            registration.workspaceBindingId(organizationId, team.team().id()),
            ProviderBindingTarget.workspace(team.defaultWorkspace()),
            ProviderOwner.team(team.team()),
            definition,
            implementation,
            Optional.empty(),
            Optional.empty(),
            registration.workspaceAccess(team.defaultWorkspace().id()),
            true,
            owner,
            NOW);
    return ProviderBindingCandidate.resolve(
        binding, definition, implementation, Optional.empty(), Optional.empty(), NOW);
  }

  private String resource() {
    return "/api/v1/organizations/"
        + organizationId
        + "/teams/"
        + team.team().id()
        + "/provider-bindings";
  }

  private static BuiltInProviderRegistration registration() {
    return new BuiltInProviderRegistration(
        "work-item",
        ProviderType.WORK_ITEM,
        "1.0.0",
        "CrewScope WorkItem",
        "native-work-item",
        "1.0.0",
        ProviderCapabilities.of(
            "workitem.read",
            "workitem.create",
            "workitem.update",
            "workitem.comment",
            "workitem.resource-link"));
  }
}
