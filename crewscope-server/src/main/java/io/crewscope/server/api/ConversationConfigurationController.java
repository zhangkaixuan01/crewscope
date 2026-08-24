package io.crewscope.server.api;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.conversation.ConversationConfigurationRefreshService;
import io.crewscope.application.conversation.ConversationConfigurationStatus;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.conversation.AgentRuntimeConfigurationPin;
import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Owner-only Conversation configuration pin and safe-point refresh HTTP boundary. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/conversations")
public final class ConversationConfigurationController {

    private final ConversationConfigurationRefreshService service;
    private final TeamRequestIdentityResolver identityResolver;

    public ConversationConfigurationController(
            ConversationConfigurationRefreshService service,
            TeamRequestIdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @GetMapping("/{conversationId}/agent-configuration")
    public Mono<ResponseEntity<ConversationConfigurationResponse>> status(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String conversationId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        ConversationId conversation = conversationId(conversationId);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.status(
                                access, organization, team, conversation))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .eTag(Long.toString(value.session().version()))
                        .body(ConversationConfigurationResponse.from(value)));
    }

    @PostMapping("/{conversationId}/agent-configuration-refresh")
    public Mono<ResponseEntity<CommandReceiptResponse>> refresh(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String conversationId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        ConversationId conversation = conversationId(conversationId);
        long expectedVersion = ApiHeaders.requireIfMatch(ifMatch);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        return command(
                authentication,
                organization,
                idempotencyKey,
                exchange,
                context -> service.refresh(
                        context, team, conversation, expectedVersion));
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

    private static ConversationId conversationId(String value) {
        try {
            return ConversationId.from(value);
        } catch (RuntimeException failure) {
            throw invalidField("conversationId");
        }
    }

    private static ApiRequestException invalidField(String field) {
        return new ApiRequestException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid Conversation configuration field",
                Map.of("field", field));
    }

    public record ConversationConfigurationResponse(
            String runtimeSessionId,
            long runtimeSessionVersion,
            String agentProfileId,
            Long pinnedConfigurationRevision,
            String pinnedConfigurationHash,
            long currentConfigurationRevision,
            String currentConfigurationHash,
            boolean refreshRequired) {
        static ConversationConfigurationResponse from(ConversationConfigurationStatus value) {
            AgentRuntimeSession session = value.session();
            Optional<AgentRuntimeConfigurationPin> pin = session.configurationPin();
            return new ConversationConfigurationResponse(
                    session.id().toString(),
                    session.version(),
                    session.agentProfileId().toString(),
                    pin.flatMap(AgentRuntimeConfigurationPin::configurationRevision)
                            .map(revision -> revision.value())
                            .orElse(null),
                    pin.flatMap(AgentRuntimeConfigurationPin::configurationHash)
                            .map(Object::toString)
                            .orElse(null),
                    value.currentConfiguration().revision().value(),
                    value.currentConfiguration().configurationHash().toString(),
                    value.refreshRequired());
        }
    }
}
