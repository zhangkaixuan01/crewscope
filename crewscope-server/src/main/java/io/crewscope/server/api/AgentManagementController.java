package io.crewscope.server.api;

import io.crewscope.application.agent.AgentManagementApplicationService;
import io.crewscope.application.agent.CreateAgentRequest;
import io.crewscope.application.agent.ManagedAgentView;
import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentExecutionModelBinding;
import io.crewscope.domain.agent.AgentModelSelection;
import io.crewscope.domain.agent.AgentOwnershipType;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplateKey;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.agent.AgentTemplateVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Team-scoped HTTP boundary for trusted Agent templates and stable Agent lifecycle management. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}")
public final class AgentManagementController {

    private final AgentManagementApplicationService service;
    private final TeamRequestIdentityResolver identityResolver;

    public AgentManagementController(
            AgentManagementApplicationService service,
            TeamRequestIdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @GetMapping("/agent-templates")
    public Mono<ResponseEntity<TemplateListResponse>> templates(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestParam String ownershipType,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100) int offset,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        AgentOwnershipType ownership = ownershipType(ownershipType);
        int pageSize = ApiPagination.limit(limit);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.listTemplates(
                                access, organization, team, ownership, offset, pageSize))
                .map(values -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(TemplateListResponse.from(values)));
    }

    @PostMapping("/agent-profiles")
    public Mono<ResponseEntity<CommandReceiptResponse>> create(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody CreateAgentBody request,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        CreateAgentRequest command = createRequest(organization, team, request);
        return command(
                authentication,
                organization,
                idempotencyKey,
                exchange,
                context -> service.create(context, team, command));
    }

    @GetMapping("/agent-profiles")
    public Mono<ResponseEntity<AgentListResponse>> list(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestParam(defaultValue = "0") @Min(0) int offset,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        int pageSize = ApiPagination.limit(limit);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.list(access, organization, team, offset, pageSize))
                .map(values -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(AgentListResponse.from(values)));
    }

    @GetMapping("/agent-profiles/{profileId}")
    public Mono<ResponseEntity<AgentResponse>> get(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String profileId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        AgentProfileId profile = profileId(profileId);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.get(access, organization, team, profile))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .eTag(Long.toString(value.profile().version()))
                        .body(AgentResponse.from(value)));
    }

    @GetMapping("/agent-profiles/{profileId}/configurations")
    public Mono<ResponseEntity<ConfigurationListResponse>> configurations(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String profileId,
            @RequestParam(defaultValue = "0") @Min(0) int offset,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        AgentProfileId profile = profileId(profileId);
        int pageSize = ApiPagination.limit(limit);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.configurationHistory(
                                access, organization, team, profile, offset, pageSize))
                .map(values -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(ConfigurationListResponse.from(values)));
    }

    @PostMapping("/agent-profiles/{profileId}/activate")
    public Mono<ResponseEntity<CommandReceiptResponse>> activate(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String profileId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication,
            ServerWebExchange exchange) {
        return transition(
                organizationId,
                teamId,
                profileId,
                key,
                ifMatch,
                authentication,
                exchange,
                context -> service.activate(
                        context,
                        teamId(teamId),
                        profileId(profileId),
                        ApiHeaders.requireIfMatch(ifMatch)));
    }

    @PostMapping("/agent-profiles/{profileId}/disable")
    public Mono<ResponseEntity<CommandReceiptResponse>> disable(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String profileId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication,
            ServerWebExchange exchange) {
        return transition(
                organizationId,
                teamId,
                profileId,
                key,
                ifMatch,
                authentication,
                exchange,
                context -> service.disable(
                        context,
                        teamId(teamId),
                        profileId(profileId),
                        ApiHeaders.requireIfMatch(ifMatch)));
    }

    @PostMapping("/agent-profiles/{profileId}/archive")
    public Mono<ResponseEntity<CommandReceiptResponse>> archive(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String profileId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication,
            ServerWebExchange exchange) {
        return transition(
                organizationId,
                teamId,
                profileId,
                key,
                ifMatch,
                authentication,
                exchange,
                context -> service.archive(
                        context,
                        teamId(teamId),
                        profileId(profileId),
                        ApiHeaders.requireIfMatch(ifMatch)));
    }

    private Mono<ResponseEntity<CommandReceiptResponse>> transition(
            String organizationValue,
            String teamValue,
            String profileValue,
            String key,
            String ifMatch,
            Authentication authentication,
            ServerWebExchange exchange,
            Function<TeamCommandContext, CommandExecution<AgentProfile>> action) {
        OrganizationId organization = organizationId(organizationValue);
        teamId(teamValue);
        profileId(profileValue);
        ApiHeaders.requireIfMatch(ifMatch);
        return command(
                authentication,
                organization,
                ApiHeaders.requireIdempotencyKey(key),
                exchange,
                action);
    }

    private <T> Mono<ResponseEntity<CommandReceiptResponse>> command(
            Authentication authentication,
            OrganizationId organizationId,
            IdempotencyKey idempotencyKey,
            ServerWebExchange exchange,
            Function<TeamCommandContext, CommandExecution<T>> action) {
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identityResolver
                .resolve(authentication, organizationId, correlationId)
                .flatMap(access -> blocking(() -> action.apply(new TeamCommandContext(
                        access, idempotencyKey, correlationId, Optional.empty()))))
                .map(CommandReceiptResponse::accepted);
    }

    private <T> Mono<T> query(
            Authentication authentication,
            OrganizationId organizationId,
            ServerWebExchange exchange,
            Function<TeamAccessContext, T> action) {
        return identityResolver
                .resolve(authentication, organizationId, ApiCorrelationIds.resolve(exchange))
                .flatMap(access -> blocking(() -> action.apply(access)));
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static CreateAgentRequest createRequest(
            OrganizationId organizationId, TeamId teamId, CreateAgentBody request) {
        try {
            AgentTemplatePublisherScope publisher = switch (request.publisherType()) {
                case "ORGANIZATION" -> AgentTemplatePublisherScope.organization(organizationId);
                case "TEAM" -> AgentTemplatePublisherScope.team(organizationId, teamId);
                default -> throw new IllegalArgumentException("invalid publisherType");
            };
            AgentTemplateKey key = new AgentTemplateKey(request.templateKey());
            return new CreateAgentRequest(
                    ownershipType(request.ownershipType()),
                    publisher,
                    new AgentTemplateVersion(key, request.templateVersion()),
                    request.displayName());
        } catch (RuntimeException failure) {
            throw invalidField("agent");
        }
    }

    private static OrganizationId organizationId(String value) {
        try {
            return OrganizationId.from(value);
        } catch (RuntimeException failure) {
            throw invalidField("organizationId");
        }
    }

    private static TeamId teamId(String value) {
        try {
            return TeamId.from(value);
        } catch (RuntimeException failure) {
            throw invalidField("teamId");
        }
    }

    private static AgentProfileId profileId(String value) {
        try {
            return AgentProfileId.from(value);
        } catch (RuntimeException failure) {
            throw invalidField("profileId");
        }
    }

    private static AgentOwnershipType ownershipType(String value) {
        try {
            return AgentOwnershipType.valueOf(value);
        } catch (RuntimeException failure) {
            throw invalidField("ownershipType");
        }
    }

    private static ApiRequestException invalidField(String field) {
        return new ApiRequestException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid Agent field",
                Map.of("field", field));
    }

    public record CreateAgentBody(
            @NotBlank String publisherType,
            @NotBlank String templateKey,
            @Min(1) long templateVersion,
            @NotBlank String ownershipType,
            @NotBlank @Size(max = 200) String displayName) {}

    public record TemplateListResponse(List<TemplateResponse> items) {
        static TemplateListResponse from(List<AgentTemplateDefinition> values) {
            return new TemplateListResponse(values.stream().map(TemplateResponse::from).toList());
        }
    }

    /** System Prompt, raw schema and runtime implementation details remain server-internal. */
    public record TemplateResponse(
            String publisherType,
            String publisherId,
            String key,
            long version,
            String runtimeRole,
            List<String> allowedOwnershipTypes,
            List<String> allowedExecutionScopes,
            List<String> declaredCapabilities,
            List<String> requiredModelCapabilities,
            List<String> approvedSkillKeys,
            List<String> memberConfigurableSlots,
            List<String> administratorConfigurableSlots,
            String contentHash,
            String status,
            long lifecycleVersion) {
        static TemplateResponse from(AgentTemplateDefinition value) {
            return new TemplateResponse(
                    value.publisherScope().teamId().isPresent() ? "TEAM" : "ORGANIZATION",
                    value.publisherScope().teamId()
                            .<String>map(Object::toString)
                            .orElse(value.publisherScope().organizationId().toString()),
                    value.templateVersion().key().toString(),
                    value.templateVersion().version(),
                    value.runtimeRole().name(),
                    value.allowedOwnershipTypes().stream().map(Enum::name).sorted().toList(),
                    value.allowedExecutionScopes().stream().map(Enum::name).sorted().toList(),
                    value.capabilities().declaredCapabilities().stream()
                            .map(Object::toString).sorted().toList(),
                    value.capabilities().requiredModelCapabilities().stream()
                            .map(Object::toString).sorted().toList(),
                    value.policy().approvedSkillKeys().stream().sorted().toList(),
                    value.policy().memberConfigurableSlots().stream()
                            .map(Enum::name).sorted().toList(),
                    value.policy().administratorConfigurableSlots().stream()
                            .map(Enum::name).sorted().toList(),
                    value.contentHash().toString(),
                    value.status().name(),
                    value.lifecycleVersion());
        }
    }

    public record AgentListResponse(List<AgentResponse> items) {
        static AgentListResponse from(List<ManagedAgentView> values) {
            return new AgentListResponse(values.stream().map(AgentResponse::from).toList());
        }
    }

    public record AgentResponse(
            String id,
            String principalId,
            String displayName,
            String principalStatus,
            String organizationId,
            String teamId,
            String workspaceId,
            String ownershipType,
            String ownerMemberId,
            String runtimeRole,
            String templateKey,
            long templateVersion,
            boolean defaultProfile,
            String status,
            Long currentConfigurationRevision,
            String currentConfigurationHash,
            String createdAt,
            String updatedAt,
            long version) {
        static AgentResponse from(ManagedAgentView view) {
            AgentProfile value = view.profile();
            return new AgentResponse(
                    value.id().toString(),
                    value.agentPrincipalId().toString(),
                    view.principal().displayName(),
                    view.principal().status().name(),
                    value.scope().organizationId().toString(),
                    value.scope().teamId().map(Object::toString).orElse(null),
                    value.workspaceId().toString(),
                    value.ownership().type().name(),
                    value.ownership().ownerMemberId().map(Object::toString).orElse(null),
                    value.runtimeRole().name(),
                    value.templateVersion().key().toString(),
                    value.templateVersion().version(),
                    value.defaultProfile(),
                    value.status().name(),
                    view.currentConfiguration()
                            .map(configuration -> configuration.revision().value())
                            .orElse(null),
                    view.currentConfiguration()
                            .map(configuration -> configuration.configurationHash().toString())
                            .orElse(null),
                    value.audit().createdAt().toString(),
                    value.audit().updatedAt().toString(),
                    value.version());
        }
    }

    public record ConfigurationListResponse(List<ConfigurationResponse> items) {
        static ConfigurationListResponse from(List<AgentConfigurationVersion> values) {
            return new ConfigurationListResponse(
                    values.stream().map(ConfigurationResponse::from).toList());
        }
    }

    /** Immutable public configuration evidence without Prompt, Tool payload or credential facts. */
    public record ConfigurationResponse(
            long revision,
            Long previousRevision,
            String templateKey,
            long templateVersion,
            String templateContentHash,
            BindingResponse personalBinding,
            BindingResponse teamBinding,
            String configurationHash,
            String createdAt,
            String createdBy) {
        static ConfigurationResponse from(AgentConfigurationVersion value) {
            return new ConfigurationResponse(
                    value.revision().value(),
                    value.previousRevision().map(revision -> revision.value()).orElse(null),
                    value.templateVersion().key().toString(),
                    value.templateVersion().version(),
                    value.templateContentHash().toString(),
                    value.personalModelBinding().map(BindingResponse::from).orElse(null),
                    value.teamModelBinding().map(BindingResponse::from).orElse(null),
                    value.configurationHash().toString(),
                    value.audit().createdAt().toString(),
                    value.audit().createdBy().orElseThrow().toString());
        }
    }

    public record BindingResponse(
            String executionScope,
            String kind,
            ModelSelectionResponse primary,
            ModelSelectionResponse fallback) {
        static BindingResponse from(AgentExecutionModelBinding value) {
            return new BindingResponse(
                    value.executionScope().name(),
                    value.kind().name(),
                    value.directBinding()
                            .map(binding -> ModelSelectionResponse.from(binding.primary()))
                            .orElse(null),
                    value.directBinding()
                            .flatMap(binding -> binding.fallback())
                            .map(ModelSelectionResponse::from)
                            .orElse(null));
        }
    }

    public record ModelSelectionResponse(
            String connectionId,
            String providerKey,
            String catalogEntryId,
            String modelId,
            long catalogRevision) {
        static ModelSelectionResponse from(AgentModelSelection value) {
            return new ModelSelectionResponse(
                    value.connectionId().toString(),
                    value.providerKey().toString(),
                    value.catalogCoordinate().entryId().toString(),
                    value.catalogCoordinate().modelId().toString(),
                    value.catalogCoordinate().catalogRevision().value());
        }
    }
}
