package io.crewscope.server.api;

import io.crewscope.application.coding.CreateRepositoryBindingCommand;
import io.crewscope.application.coding.RepositoryBindingApplicationService;
import io.crewscope.application.coding.RepositoryBindingPreflightResult;
import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** HTTP boundary for path-free, WorkProject-scoped managed RepositoryBinding operations. */
@RestController
@RequestMapping(
        "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}"
                + "/repository-bindings")
public final class RepositoryBindingController {

    private final RepositoryBindingApplicationService service;
    private final TeamRequestIdentityResolver identityResolver;

    public RepositoryBindingController(
            RepositoryBindingApplicationService service,
            TeamRequestIdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @PostMapping
    public Mono<ResponseEntity<CommandReceiptResponse>> create(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String projectId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody RepositoryBindingRequest request,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        WorkProjectId project = projectId(projectId);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        return command(
                authentication,
                organization,
                idempotencyKey,
                exchange,
                context -> service.create(
                        context,
                        team,
                        project,
                        new CreateRepositoryBindingCommand(
                                request.repositoryKey(), request.defaultBranch())));
    }

    @GetMapping
    public Mono<ResponseEntity<RepositoryBindingListResponse>> list(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String projectId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        WorkProjectId project = projectId(projectId);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.list(access, organization, team, project))
                .map(values -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(RepositoryBindingListResponse.from(values)));
    }

    @GetMapping("/{bindingId}")
    public Mono<ResponseEntity<RepositoryBindingResponse>> get(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String projectId,
            @PathVariable String bindingId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        WorkProjectId project = projectId(projectId);
        RepositoryBindingId binding = bindingId(bindingId);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.get(access, organization, team, project, binding))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .eTag(Long.toString(value.version()))
                        .body(RepositoryBindingResponse.from(value)));
    }

    @PostMapping("/preflight")
    public Mono<ResponseEntity<RepositoryPreflightResponse>> preflightDraft(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String projectId,
            @Valid @RequestBody RepositoryBindingRequest request,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        WorkProjectId project = projectId(projectId);
        RepositoryKey repositoryKey = repositoryKey(request.repositoryKey());
        RepositoryBranchName baselineRef = branch(request.defaultBranch());
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.preflightDraft(
                                access,
                                organization,
                                team,
                                project,
                                repositoryKey,
                                baselineRef))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(RepositoryPreflightResponse.from(value)));
    }

    @PostMapping("/{bindingId}/preflight")
    public Mono<ResponseEntity<RepositoryPreflightResponse>> preflightExisting(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String projectId,
            @PathVariable String bindingId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        WorkProjectId project = projectId(projectId);
        RepositoryBindingId binding = bindingId(bindingId);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.preflightExisting(
                                access, organization, team, project, binding))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(RepositoryPreflightResponse.from(value)));
    }

    @PostMapping("/{bindingId}/activate")
    public Mono<ResponseEntity<CommandReceiptResponse>> activate(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String projectId,
            @PathVariable String bindingId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication,
            ServerWebExchange exchange) {
        return transition(
                organizationId,
                teamId,
                projectId,
                bindingId,
                key,
                ifMatch,
                authentication,
                exchange,
                (context, team, project, binding, version) ->
                        service.activate(context, team, project, binding, version));
    }

    @PostMapping("/{bindingId}/disable")
    public Mono<ResponseEntity<CommandReceiptResponse>> disable(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String projectId,
            @PathVariable String bindingId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication,
            ServerWebExchange exchange) {
        return transition(
                organizationId,
                teamId,
                projectId,
                bindingId,
                key,
                ifMatch,
                authentication,
                exchange,
                (context, team, project, binding, version) ->
                        service.disable(context, team, project, binding, version));
    }

    private Mono<ResponseEntity<CommandReceiptResponse>> transition(
            String organizationValue,
            String teamValue,
            String projectValue,
            String bindingValue,
            String key,
            String ifMatch,
            Authentication authentication,
            ServerWebExchange exchange,
            TransitionAction action) {
        OrganizationId organization = organizationId(organizationValue);
        TeamId team = teamId(teamValue);
        WorkProjectId project = projectId(projectValue);
        RepositoryBindingId binding = bindingId(bindingValue);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        long expectedVersion = ApiHeaders.requireIfMatch(ifMatch);
        return command(
                authentication,
                organization,
                idempotencyKey,
                exchange,
                context -> action.apply(context, team, project, binding, expectedVersion));
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

    private static OrganizationId organizationId(String value) {
        try {
            return OrganizationId.from(value);
        } catch (IllegalArgumentException exception) {
            throw invalidIdentifier("organizationId");
        }
    }

    private static TeamId teamId(String value) {
        try {
            return TeamId.from(value);
        } catch (IllegalArgumentException exception) {
            throw invalidIdentifier("teamId");
        }
    }

    private static WorkProjectId projectId(String value) {
        try {
            return WorkProjectId.from(value);
        } catch (IllegalArgumentException exception) {
            throw invalidIdentifier("projectId");
        }
    }

    private static RepositoryBindingId bindingId(String value) {
        try {
            return RepositoryBindingId.from(value);
        } catch (IllegalArgumentException exception) {
            throw invalidIdentifier("bindingId");
        }
    }

    private static RepositoryKey repositoryKey(String value) {
        try {
            return RepositoryKey.parse(value);
        } catch (IllegalArgumentException | DomainValidationException exception) {
            throw invalidField("repositoryKey");
        }
    }

    private static RepositoryBranchName branch(String value) {
        try {
            return new RepositoryBranchName(value);
        } catch (IllegalArgumentException | DomainValidationException exception) {
            throw invalidField("defaultBranch");
        }
    }

    private static ApiRequestException invalidIdentifier(String field) {
        return new ApiRequestException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid identifier",
                Map.of("field", field));
    }

    private static ApiRequestException invalidField(String field) {
        return new ApiRequestException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid RepositoryBinding field",
                Map.of("field", field));
    }

    public record RepositoryBindingRequest(
            @NotBlank @Pattern(regexp = RepositoryKey.FORMAT_REGEX) String repositoryKey,
            @NotBlank @Size(max = RepositoryBranchName.MAX_LENGTH) String defaultBranch) {}

    public record RepositoryBindingListResponse(List<RepositoryBindingResponse> items) {

        static RepositoryBindingListResponse from(List<RepositoryBinding> bindings) {
            return new RepositoryBindingListResponse(
                    bindings.stream().map(RepositoryBindingResponse::from).toList());
        }
    }

    public record RepositoryBindingResponse(
            String id,
            String organizationId,
            String teamId,
            String workspaceId,
            String projectId,
            String kind,
            String repositoryKey,
            String defaultBranch,
            String status,
            long version,
            String createdAt,
            String createdByPrincipalId,
            String updatedAt,
            String updatedByPrincipalId) {

        static RepositoryBindingResponse from(RepositoryBinding binding) {
            return new RepositoryBindingResponse(
                    binding.id().toString(),
                    binding.scope().organizationId().toString(),
                    binding.scope().teamId().toString(),
                    binding.scope().workspaceId().toString(),
                    binding.scope().workProjectId().toString(),
                    binding.kind().name(),
                    binding.repositoryKey().value(),
                    binding.defaultBranch().value(),
                    binding.status().name(),
                    binding.version(),
                    binding.audit().createdAt().toString(),
                    binding.audit().createdBy().map(Object::toString).orElse(null),
                    binding.audit().updatedAt().toString(),
                    binding.audit().updatedBy().map(Object::toString).orElse(null));
        }
    }

    public record RepositoryPreflightResponse(
            boolean ready, String repositoryKey, String baselineRef, String baselineCommit) {

        static RepositoryPreflightResponse from(RepositoryBindingPreflightResult result) {
            return new RepositoryPreflightResponse(
                    true,
                    result.repositoryKey().value(),
                    result.baselineRef().value(),
                    result.baselineCommit().value());
        }
    }

    @FunctionalInterface
    private interface TransitionAction {
        CommandExecution<RepositoryBinding> apply(
                TeamCommandContext context,
                TeamId teamId,
                WorkProjectId projectId,
                RepositoryBindingId bindingId,
                long expectedVersion);
    }
}
