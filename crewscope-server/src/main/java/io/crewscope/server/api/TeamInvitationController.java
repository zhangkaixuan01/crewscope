package io.crewscope.server.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.identity.AccountOrganizationResolution;
import io.crewscope.application.identity.AuthenticatedAccountOrganizationResolver;
import io.crewscope.application.team.AuthenticatedInvitationCommandContext;
import io.crewscope.application.team.CreateTeamInvitationCommand;
import io.crewscope.application.team.InvitationToken;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamInvitationApplicationService;
import io.crewscope.application.team.TeamInvitationCursor;
import io.crewscope.application.team.TeamInvitationIssueResult;
import io.crewscope.application.team.TeamInvitationPage;
import io.crewscope.application.team.TeamInvitationPreview;
import io.crewscope.domain.identity.NormalizedEmail;
import io.crewscope.domain.identity.SecurityVersion;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.team.TeamInvitation;
import io.crewscope.domain.team.TeamInvitationId;
import io.crewscope.server.config.RegistrationProperties;
import io.crewscope.server.security.session.BrowserSessionPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Team invitation management plus token-only preview and current-account acceptance boundary. */
@RestController
@ConditionalOnProperty(
        name = "crewscope.invitation.token.enabled",
        havingValue = "true")
public final class TeamInvitationController {

    private final TeamInvitationApplicationService invitations;
    private final TeamRequestIdentityResolver teamIdentities;
    private final AuthenticatedAccountOrganizationResolver accountResolver;
    private final RegistrationProperties registration;
    private final TeamInvitationCursorCodec cursors = new TeamInvitationCursorCodec();

    public TeamInvitationController(
            TeamInvitationApplicationService invitations,
            TeamRequestIdentityResolver teamIdentities,
            AuthenticatedAccountOrganizationResolver accountResolver,
            RegistrationProperties registration) {
        this.invitations = invitations;
        this.teamIdentities = teamIdentities;
        this.accountResolver = accountResolver;
        this.registration = registration;
    }

    @PostMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/invitations")
    public Mono<ResponseEntity<InvitationCreationResponse>> create(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) List<String> keys,
            @Valid @RequestBody CreateInvitationRequest request,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        IdempotencyKey idempotencyKey = ApiHeaders.requireSingleIdempotencyKey(keys);
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        CreateTeamInvitationCommand command = request.toCommand();
        return teamIdentities
                .resolve(authentication, organization, correlationId)
                .flatMap(access -> blocking(() -> invitations.create(
                        new TeamCommandContext(
                                access,
                                idempotencyKey,
                                correlationId,
                                Optional.empty()),
                        team,
                        command)))
                .map(InvitationCreationResponse::accepted);
    }

    @GetMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/invitations")
    public Mono<ResponseEntity<InvitationPageResponse>> list(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        Optional<TeamInvitationCursor> cursor = Optional.ofNullable(after).map(cursors::decode);
        int pageSize = ApiPagination.limit(limit);
        return teamIdentities
                .resolve(authentication, organization, ApiCorrelationIds.resolve(exchange))
                .flatMap(access -> blocking(() ->
                        invitations.list(access, organization, team, cursor, pageSize)))
                .map(page -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(InvitationPageResponse.from(page, cursors)));
    }

    @PostMapping(
            "/api/v1/organizations/{organizationId}/teams/{teamId}"
                    + "/invitations/{invitationId}/revoke")
    public Mono<ResponseEntity<CommandReceiptResponse>> revoke(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String invitationId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) List<String> keys,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        TeamInvitationId invitation = invitationId(invitationId);
        IdempotencyKey idempotencyKey = ApiHeaders.requireSingleIdempotencyKey(keys);
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return teamIdentities
                .resolve(authentication, organization, correlationId)
                .flatMap(access -> blocking(() -> invitations.revoke(
                        new TeamCommandContext(
                                access,
                                idempotencyKey,
                                correlationId,
                                Optional.empty()),
                        team,
                        invitation)))
                .map(CommandReceiptResponse::accepted);
    }

    @PostMapping("/api/v1/invitations/preview")
    public Mono<ResponseEntity<InvitationPreviewResponse>> preview(
            @Valid @RequestBody InvitationTokenRequest request) {
        InvitationToken token = invitationToken(request.token());
        return blocking(() -> invitations.preview(token))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(InvitationPreviewResponse.from(value)));
    }

    @PostMapping("/api/v1/invitations/accept")
    public Mono<ResponseEntity<CommandReceiptResponse>> accept(
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) List<String> keys,
            @Valid @RequestBody InvitationTokenRequest request,
            Authentication authentication,
            ServerWebExchange exchange) {
        IdempotencyKey idempotencyKey = ApiHeaders.requireSingleIdempotencyKey(keys);
        InvitationToken token = invitationToken(request.token());
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return resolvedAccount(authentication)
                .flatMap(resolution -> blocking(() -> invitations.accept(
                        new AuthenticatedInvitationCommandContext(
                                resolution.account(),
                                resolution.binding(),
                                new TeamAccessContext(
                                        resolution.principal(),
                                        resolution.account().allowsPlatformOperations()),
                                idempotencyKey,
                                correlationId,
                                Optional.empty()),
                        token)))
                .map(CommandReceiptResponse::accepted);
    }

    private Mono<AccountOrganizationResolution> resolvedAccount(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal()
                        instanceof BrowserSessionPrincipal sessionPrincipal)) {
            return Mono.error(unauthenticated());
        }
        return blocking(() -> accountResolver
                .resolveSession(
                        new UserAccountId(sessionPrincipal.accountId()),
                        new SecurityVersion(sessionPrincipal.securityVersion()),
                        configuredOrganization())
                .orElseThrow(TeamInvitationController::unauthenticated));
    }

    private OrganizationId configuredOrganization() {
        String configured = registration.getOrganizationId();
        if (configured == null || configured.isBlank()) {
            throw invitationUnavailable();
        }
        try {
            return OrganizationId.from(configured);
        } catch (RuntimeException invalid) {
            throw invitationUnavailable();
        }
    }

    private static ApiRequestException invitationUnavailable() {
        return new ApiRequestException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "invitation_unavailable",
                "Invitation service is unavailable",
                Map.of());
    }

    private static InvitationToken invitationToken(String value) {
        try {
            return new InvitationToken(value);
        } catch (RuntimeException invalid) {
            throw new ApiRequestException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "invitation_invalid",
                    "Invitation could not be processed",
                    Map.of());
        }
    }

    private static OrganizationId organizationId(String value) {
        try {
            return OrganizationId.from(value);
        } catch (IllegalArgumentException invalid) {
            throw invalidIdentifier("organizationId");
        }
    }

    private static TeamId teamId(String value) {
        try {
            return TeamId.from(value);
        } catch (IllegalArgumentException invalid) {
            throw invalidIdentifier("teamId");
        }
    }

    private static TeamInvitationId invitationId(String value) {
        try {
            return TeamInvitationId.from(value);
        } catch (IllegalArgumentException invalid) {
            throw invalidIdentifier("invitationId");
        }
    }

    private static ApiRequestException invalidIdentifier(String field) {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid identifier",
                Map.of("field", field));
    }

    private static ApiRequestException unauthenticated() {
        return new ApiRequestException(
                HttpStatus.UNAUTHORIZED,
                "authentication_required",
                "Authentication is required",
                Map.of());
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    public record CreateInvitationRequest(
            @Size(max = NormalizedEmail.MAX_LENGTH) String targetEmail,
            BuiltInTeamRole targetRole,
            @Min(1) @Max(43_200) long expiresInMinutes) {

        CreateTeamInvitationCommand toCommand() {
            if (targetRole == null || targetRole == BuiltInTeamRole.TEAM_OWNER) {
                throw invalidField("targetRole");
            }
            Optional<NormalizedEmail> normalizedEmail;
            try {
                normalizedEmail = Optional.ofNullable(targetEmail)
                        .filter(value -> !value.isBlank())
                        .map(NormalizedEmail::fromDisplayValue);
            } catch (DomainValidationException | IllegalArgumentException invalid) {
                throw invalidField("targetEmail");
            }
            return new CreateTeamInvitationCommand(
                    normalizedEmail, targetRole, Duration.ofMinutes(expiresInMinutes));
        }

        private static ApiRequestException invalidField(String field) {
            return new ApiRequestException(
                    HttpStatus.BAD_REQUEST,
                    "invalid_request",
                    "Request contains an invalid invitation field",
                    Map.of("field", field));
        }

        /** Rejects inviter, Team scope, token material and lifecycle fields from the client. */
        @JsonAnySetter
        void rejectUnknownProperty(String ignoredName, Object ignoredValue) {
            throw new IllegalArgumentException("Unsupported invitation property");
        }
    }

    public record InvitationTokenRequest(
            @NotBlank @Size(min = InvitationToken.ENCODED_LENGTH,
                    max = InvitationToken.ENCODED_LENGTH) String token) {

        /** Keeps account, membership, role and Invitation lifecycle facts server-owned. */
        @JsonAnySetter
        void rejectUnknownProperty(String ignoredName, Object ignoredValue) {
            throw new IllegalArgumentException("Unsupported invitation-token property");
        }
    }

    public record InvitationCreationResponse(
            CommandReceiptResponse command,
            InvitationResponse invitation,
            String token) {

        static ResponseEntity<InvitationCreationResponse> accepted(
                CommandExecution<TeamInvitationIssueResult> execution) {
            ResponseEntity.BodyBuilder response =
                    ResponseEntity.accepted().cacheControl(CacheControl.noStore());
            if (execution.replayed()) {
                response.header(ApiHeaders.IDEMPOTENCY_REPLAYED, "true");
            }
            TeamInvitationIssueResult result = execution.result().orElse(null);
            return response.body(new InvitationCreationResponse(
                    CommandReceiptResponse.from(execution.receipt()),
                    result == null ? null : InvitationResponse.from(result.invitation()),
                    result == null ? null : result.revealToken()));
        }
    }

    public record InvitationPageResponse(
            List<InvitationResponse> items, String nextCursor) {

        static InvitationPageResponse from(
                TeamInvitationPage page, TeamInvitationCursorCodec codec) {
            return new InvitationPageResponse(
                    page.invitations().stream().map(InvitationResponse::from).toList(),
                    page.nextCursor().map(codec::encode).orElse(null));
        }
    }

    public record InvitationResponse(
            String id,
            String organizationId,
            String teamId,
            String invitedByPrincipalId,
            String targetEmail,
            String targetRole,
            String status,
            String expiresAt,
            String acceptedMemberId,
            String resolvedAt,
            long version,
            String createdAt,
            String updatedAt) {

        static InvitationResponse from(TeamInvitation invitation) {
            return new InvitationResponse(
                    invitation.id().toString(),
                    invitation.scope().organizationId().toString(),
                    invitation.scope().teamId().toString(),
                    invitation.invitedByPrincipalId().toString(),
                    invitation.targetEmail().map(NormalizedEmail::value).orElse(null),
                    invitation.targetRole().name(),
                    invitation.status().name(),
                    invitation.expiresAt().toString(),
                    invitation.acceptedMemberId().map(Object::toString).orElse(null),
                    invitation.resolvedAt().map(Object::toString).orElse(null),
                    invitation.version(),
                    invitation.lifecycle().createdAt().toString(),
                    invitation.lifecycle().updatedAt().toString());
        }
    }

    public record InvitationPreviewResponse(
            String state,
            String invitationId,
            String teamName,
            String targetRole,
            String expiresAt,
            boolean targetRestricted) {

        static InvitationPreviewResponse from(TeamInvitationPreview preview) {
            return new InvitationPreviewResponse(
                    preview.state().name(),
                    preview.invitationId().map(Object::toString).orElse(null),
                    preview.teamName().orElse(null),
                    preview.targetRole().map(Enum::name).orElse(null),
                    preview.expiresAt().map(Object::toString).orElse(null),
                    preview.targetRestricted());
        }
    }
}
