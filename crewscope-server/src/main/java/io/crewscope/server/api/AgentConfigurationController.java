package io.crewscope.server.api;

import io.crewscope.application.agent.AgentConfigurationApplicationService;
import io.crewscope.application.agent.AgentConfigurationDraft;
import io.crewscope.application.agent.AgentModelBindingDraft;
import io.crewscope.application.agent.AgentModelSelectionDraft;
import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.model.SelectableModelOption;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.agent.AgentBudgetPolicyReference;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentMemoryPolicyReference;
import io.crewscope.domain.agent.AgentModelBindingKind;
import io.crewscope.domain.agent.AgentReasoningMode;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.agent.ResolvedAgentModelDefault;
import io.crewscope.domain.agent.ResolvedModelSelection;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.model.ModelCatalogEntryId;
import io.crewscope.domain.model.ModelCatalogRevision;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelTokenPrice;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workspace.AgentProfileId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

/** Team-scoped HTTP boundary for Agent bindings, controlled preferences and Preflight. */
@RestController
@RequestMapping(
        "/api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}")
public final class AgentConfigurationController {

    private final AgentConfigurationApplicationService service;
    private final TeamRequestIdentityResolver identityResolver;

    public AgentConfigurationController(
            AgentConfigurationApplicationService service,
            TeamRequestIdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @GetMapping("/configurations/current")
    public Mono<ResponseEntity<CurrentConfigurationResponse>> current(
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
                        access -> service.current(access, organization, team, profile))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .eTag(Long.toString(value.revision().value()))
                        .body(CurrentConfigurationResponse.from(value)));
    }

    @GetMapping("/model-catalog")
    public Mono<ResponseEntity<SelectableModelListResponse>> catalog(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String profileId,
            @RequestParam String executionScope,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        AgentProfileId profile = profileId(profileId);
        AgentExecutionScope scope = executionScope(executionScope);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.selectable(
                                access, organization, team, profile, scope))
                .map(values -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(SelectableModelListResponse.from(values)));
    }

    @PostMapping("/configurations")
    public Mono<ResponseEntity<CommandReceiptResponse>> append(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String profileId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ConfigurationRequest request,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        AgentProfileId profile = profileId(profileId);
        long expectedRevision = ApiHeaders.requireIfMatch(ifMatch);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        AgentConfigurationDraft draft = configurationDraft(request);
        return command(
                authentication,
                organization,
                idempotencyKey,
                exchange,
                context -> service.append(
                        context, team, profile, expectedRevision, draft));
    }

    @PostMapping("/model-preflight")
    public Mono<ResponseEntity<PreflightResponse>> preflight(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String profileId,
            @Valid @RequestBody PreflightRequest request,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        AgentProfileId profile = profileId(profileId);
        AgentExecutionScope scope = executionScope(request.executionScope());
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.preflight(
                                access, organization, team, profile, scope))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(PreflightResponse.from(value)));
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

    private static AgentConfigurationDraft configurationDraft(ConfigurationRequest request) {
        try {
            return new AgentConfigurationDraft(
                    Optional.ofNullable(request.personalModelBinding()).map(
                            AgentConfigurationController::binding),
                    Optional.ofNullable(request.teamModelBinding()).map(
                            AgentConfigurationController::binding),
                    Optional.ofNullable(request.supplementalInstructions()),
                    Set.copyOf(request.approvedSkillKeys()),
                    Optional.ofNullable(request.memoryPolicy()).map(reference ->
                            new AgentMemoryPolicyReference(
                                    UUID.fromString(reference.id()), reference.version())),
                    Optional.ofNullable(request.budgetPolicy()).map(reference ->
                            new AgentBudgetPolicyReference(
                                    UUID.fromString(reference.id()), reference.version())),
                    generateOptions(request.generateOptions()));
        } catch (RuntimeException failure) {
            throw invalidField("configuration");
        }
    }

    private static AgentModelBindingDraft binding(BindingRequest request) {
        AgentModelBindingKind kind = AgentModelBindingKind.valueOf(request.kind());
        return switch (kind) {
            case DIRECT -> AgentModelBindingDraft.direct(
                    selection(ObjectsSupport.require(request.primary(), "primary")),
                    Optional.ofNullable(request.fallback()).map(
                            AgentConfigurationController::selection));
            case INHERIT_TEAM_DEFAULT -> AgentModelBindingDraft.inheritTeamDefault();
            case ORCHESTRATION_ONLY -> throw new IllegalArgumentException(
                    "ORCHESTRATION_ONLY is server-controlled");
        };
    }

    private static AgentModelSelectionDraft selection(SelectionRequest request) {
        return new AgentModelSelectionDraft(
                ModelConnectionId.from(request.connectionId()),
                ModelCatalogEntryId.from(request.catalogEntryId()),
                new ModelCatalogRevision(request.catalogRevision()));
    }

    private static SafeModelGenerateOptions generateOptions(GenerateOptionsRequest request) {
        if (request == null) {
            return SafeModelGenerateOptions.defaults();
        }
        return new SafeModelGenerateOptions(
                Optional.ofNullable(request.temperature()),
                Optional.ofNullable(request.topP()),
                Optional.ofNullable(request.maximumOutputTokens()),
                request.reasoningMode() == null
                        ? AgentReasoningMode.DEFAULT
                        : AgentReasoningMode.valueOf(request.reasoningMode()),
                request.cacheEnabled() == null || request.cacheEnabled(),
                request.parallelToolCalls() != null && request.parallelToolCalls(),
                Optional.ofNullable(request.seed()),
                request.maximumAttempts() == null ? 1 : request.maximumAttempts());
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

    private static AgentExecutionScope executionScope(String value) {
        try {
            return AgentExecutionScope.valueOf(value);
        } catch (RuntimeException failure) {
            throw invalidField("executionScope");
        }
    }

    private static ApiRequestException invalidField(String field) {
        return new ApiRequestException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid Agent configuration field",
                Map.of("field", field));
    }

    public record ConfigurationRequest(
            @Valid BindingRequest personalModelBinding,
            @Valid BindingRequest teamModelBinding,
            @Size(max = 20_000) String supplementalInstructions,
            @NotNull @Size(max = 100) List<@NotBlank String> approvedSkillKeys,
            @Valid PolicyReferenceRequest memoryPolicy,
            @Valid PolicyReferenceRequest budgetPolicy,
            @Valid GenerateOptionsRequest generateOptions) {}

    public record BindingRequest(
            @NotBlank String kind,
            @Valid SelectionRequest primary,
            @Valid SelectionRequest fallback) {}

    public record SelectionRequest(
            @NotBlank String connectionId,
            @NotBlank String catalogEntryId,
            @Min(1) long catalogRevision) {}

    public record PolicyReferenceRequest(@NotBlank String id, @Min(1) long version) {}

    public record GenerateOptionsRequest(
            BigDecimal temperature,
            BigDecimal topP,
            Long maximumOutputTokens,
            String reasoningMode,
            Boolean cacheEnabled,
            Boolean parallelToolCalls,
            Long seed,
            Integer maximumAttempts) {}

    public record PreflightRequest(@NotBlank String executionScope) {}

    public record CurrentConfigurationResponse(
            long revision,
            Long previousRevision,
            String templateKey,
            long templateVersion,
            String templateContentHash,
            AgentManagementController.BindingResponse personalBinding,
            AgentManagementController.BindingResponse teamBinding,
            String supplementalInstructions,
            List<String> approvedSkillKeys,
            PolicyReferenceResponse memoryPolicy,
            PolicyReferenceResponse budgetPolicy,
            GenerateOptionsResponse generateOptions,
            String policyPackId,
            long policyPackVersion,
            String configurationHash,
            String createdAt) {
        static CurrentConfigurationResponse from(AgentConfigurationVersion value) {
            return new CurrentConfigurationResponse(
                    value.revision().value(),
                    value.previousRevision().map(revision -> revision.value()).orElse(null),
                    value.templateVersion().key().toString(),
                    value.templateVersion().version(),
                    value.templateContentHash().toString(),
                    value.personalModelBinding()
                            .map(AgentManagementController.BindingResponse::from)
                            .orElse(null),
                    value.teamModelBinding()
                            .map(AgentManagementController.BindingResponse::from)
                            .orElse(null),
                    value.templateConfiguration().supplementalInstructions().orElse(null),
                    value.approvedSkillKeys().stream().sorted().toList(),
                    value.memoryPolicy().map(PolicyReferenceResponse::from).orElse(null),
                    value.budgetPolicy().map(PolicyReferenceResponse::from).orElse(null),
                    GenerateOptionsResponse.from(value.generateOptions()),
                    value.policyPack().id().toString(),
                    value.policyPack().version(),
                    value.configurationHash().toString(),
                    value.audit().createdAt().toString());
        }
    }

    public record PolicyReferenceResponse(String id, long version) {
        static PolicyReferenceResponse from(AgentMemoryPolicyReference value) {
            return new PolicyReferenceResponse(value.policyId().toString(), value.version());
        }

        static PolicyReferenceResponse from(AgentBudgetPolicyReference value) {
            return new PolicyReferenceResponse(value.policyId().toString(), value.version());
        }
    }

    public record GenerateOptionsResponse(
            String temperature,
            String topP,
            Long maximumOutputTokens,
            String reasoningMode,
            boolean cacheEnabled,
            boolean parallelToolCalls,
            Long seed,
            int maximumAttempts) {
        static GenerateOptionsResponse from(SafeModelGenerateOptions value) {
            return new GenerateOptionsResponse(
                    value.temperature().map(BigDecimal::toPlainString).orElse(null),
                    value.topP().map(BigDecimal::toPlainString).orElse(null),
                    value.maximumOutputTokens().orElse(null),
                    value.reasoningMode().name(),
                    value.cacheEnabled(),
                    value.parallelToolCalls(),
                    value.seed().orElse(null),
                    value.maximumAttempts());
        }
    }

    public record SelectableModelListResponse(List<SelectableModelResponse> items) {
        static SelectableModelListResponse from(List<SelectableModelOption> values) {
            return new SelectableModelListResponse(
                    values.stream().map(SelectableModelResponse::from).toList());
        }
    }

    public record SelectableModelResponse(
            String connectionId,
            String connectionOwnerType,
            String connectionOwnerId,
            String providerKey,
            String providerDisplayName,
            String catalogEntryId,
            String modelId,
            long catalogRevision,
            String modelDisplayName,
            String region,
            long contextWindowTokens,
            long maximumOutputTokens,
            List<String> capabilities,
            PriceResponse price) {
        static SelectableModelResponse from(SelectableModelOption value) {
            return new SelectableModelResponse(
                    value.selection().connectionId().toString(),
                    value.connectionOwner().type().name(),
                    value.connectionOwner().ownerId().toString(),
                    value.selection().providerKey().toString(),
                    value.providerDisplayName(),
                    value.selection().catalogCoordinate().entryId().toString(),
                    value.selection().catalogCoordinate().modelId().toString(),
                    value.selection().catalogCoordinate().catalogRevision().value(),
                    value.modelDisplayName(),
                    value.region().toString(),
                    value.contextWindowTokens(),
                    value.maximumOutputTokens(),
                    value.capabilities().stream().map(Object::toString).sorted().toList(),
                    PriceResponse.from(value.tokenPrice()));
        }
    }

    public record PriceResponse(
            String inputPerMillionTokens,
            String outputPerMillionTokens,
            String cachedInputPerMillionTokens,
            String currencyCode) {
        static PriceResponse from(ModelTokenPrice value) {
            return new PriceResponse(
                    value.inputPerMillionTokens().toPlainString(),
                    value.outputPerMillionTokens().toPlainString(),
                    value.cachedInputPerMillionTokens()
                            .map(BigDecimal::toPlainString)
                            .orElse(null),
                    value.currencyCode());
        }
    }

    public record PreflightResponse(
            String agentProfileId,
            long agentProfileVersion,
            long configurationRevision,
            String configurationHash,
            String executionScope,
            String bindingSource,
            ModelDefaultResponse modelDefault,
            ResolvedSelectionResponse primary,
            ResolvedSelectionResponse fallback,
            String resolutionHash) {
        static PreflightResponse from(ResolvedAgentExecutionConfiguration value) {
            return new PreflightResponse(
                    value.agentProfileId().toString(),
                    value.agentProfileVersion(),
                    value.configurationRevision().value(),
                    value.configurationHash().toString(),
                    value.executionScope().name(),
                    value.bindingSource().name(),
                    value.modelDefault().map(ModelDefaultResponse::from).orElse(null),
                    ResolvedSelectionResponse.from(value.primary()),
                    value.fallback().map(ResolvedSelectionResponse::from).orElse(null),
                    value.resolutionHash().toString());
        }
    }

    public record ModelDefaultResponse(
            String source,
            String scopeType,
            String scopeId,
            long revision,
            String contentHash) {
        static ModelDefaultResponse from(ResolvedAgentModelDefault value) {
            return new ModelDefaultResponse(
                    value.source().name(),
                    value.scope().teamId().isPresent() ? "TEAM" : "ORGANIZATION",
                    value.scope().teamId()
                            .<String>map(Object::toString)
                            .orElse(value.scope().organizationId().toString()),
                    value.revision().value(),
                    value.contentHash().toString());
        }
    }

    public record ResolvedSelectionResponse(
            String role,
            String providerKey,
            String connectionId,
            String connectionOwnerType,
            String connectionOwnerId,
            String region,
            String catalogEntryId,
            String modelId,
            long catalogRevision,
            String modelRevision,
            long priceRevision,
            PriceResponse price) {
        static ResolvedSelectionResponse from(ResolvedModelSelection value) {
            return new ResolvedSelectionResponse(
                    value.role().name(),
                    value.providerKey().toString(),
                    value.connectionId().toString(),
                    value.connectionOwner().type().name(),
                    value.connectionOwner().ownerId().toString(),
                    value.region().toString(),
                    value.catalogCoordinate().entryId().toString(),
                    value.catalogCoordinate().modelId().toString(),
                    value.catalogCoordinate().catalogRevision().value(),
                    value.modelRevision().toString(),
                    value.priceRevision(),
                    PriceResponse.from(value.tokenPrice()));
        }
    }

    private static final class ObjectsSupport {
        private ObjectsSupport() {}

        private static <T> T require(T value, String field) {
            if (value == null) {
                throw new IllegalArgumentException(field + " is required");
            }
            return value;
        }
    }
}
