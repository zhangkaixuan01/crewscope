package io.crewscope.infrastructure.github;

import io.crewscope.application.action.ExternalObservationRepository;
import io.crewscope.application.github.AcceptGitHubPullRequestWebhookRequest;
import io.crewscope.application.github.GitHubPullRequestWebhookPort;
import io.crewscope.application.github.GitHubPullRequestWebhookResult;
import io.crewscope.application.github.GitHubWebhookDisposition;
import io.crewscope.application.github.GitHubWebhookErrorCode;
import io.crewscope.application.github.GitHubWebhookException;
import io.crewscope.application.github.GitHubWebhookSecretResolver;
import io.crewscope.domain.action.ActionEvidenceReference;
import io.crewscope.domain.action.ExternalObjectStatus;
import io.crewscope.domain.action.ExternalObjectType;
import io.crewscope.domain.action.ExternalObservation;
import io.crewscope.domain.action.ExternalObservationKey;
import io.crewscope.domain.action.ExternalResultIdentity;
import io.crewscope.domain.action.ExternalResultSource;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** HMAC-verifies, normalizes and durably deduplicates Pull Request Webhook observations. */
public final class GitHubPullRequestWebhookAdapter implements GitHubPullRequestWebhookPort {

    private static final String EVENT_NAME = "pull_request";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Set<String> SUPPORTED_ACTIONS = Set.of(
            "opened",
            "closed",
            "reopened",
            "synchronize",
            "edited",
            "converted_to_draft",
            "ready_for_review");

    private final ObjectMapper objectMapper;
    private final GitHubWebhookSecretResolver secretResolver;
    private final ExternalObservationRepository observations;

    public GitHubPullRequestWebhookAdapter(
            ObjectMapper objectMapper,
            GitHubWebhookSecretResolver secretResolver,
            ExternalObservationRepository observations) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.secretResolver = Objects.requireNonNull(secretResolver, "secretResolver");
        this.observations = Objects.requireNonNull(observations, "observations");
    }

    @Override
    public GitHubPullRequestWebhookResult accept(
            AcceptGitHubPullRequestWebhookRequest request) {
        AcceptGitHubPullRequestWebhookRequest required = Objects.requireNonNull(
                request, "request");
        boolean signatureValid;
        try {
            signatureValid = secretResolver.useSecret(
                    required.organizationId(),
                    required.expectedIdentity().connectionId(),
                    secret -> verify(secret, required.payload(), required.signature()));
        } catch (GitHubWebhookException safeFailure) {
            throw safeFailure;
        } catch (RuntimeException unavailable) {
            throw failure(
                    GitHubWebhookErrorCode.SECRET_UNAVAILABLE,
                    "GitHub Webhook secret is unavailable");
        }
        if (!signatureValid) {
            throw failure(
                    GitHubWebhookErrorCode.SIGNATURE_INVALID,
                    "GitHub Webhook signature is invalid");
        }
        if (!EVENT_NAME.equals(required.eventName())) {
            throw failure(
                    GitHubWebhookErrorCode.EVENT_UNSUPPORTED,
                    "GitHub Webhook event is unsupported");
        }

        ExternalObservation candidate = observation(required);
        boolean inserted = observations.appendIfAbsent(
                required.organizationId(), candidate);
        if (inserted) {
            return new GitHubPullRequestWebhookResult(
                    GitHubWebhookDisposition.ACCEPTED, candidate);
        }
        ExternalObservation committed = observations
                .findObservationsByAction(required.organizationId(), required.actionId())
                .stream()
                .filter(value -> value.observationKey().equals(candidate.observationKey()))
                .findFirst()
                .orElseThrow(() -> failure(
                        GitHubWebhookErrorCode.DELIVERY_CONFLICT,
                        "GitHub Webhook delivery could not be reconciled"));
        if (!sameDelivery(committed, candidate)) {
            throw failure(
                    GitHubWebhookErrorCode.DELIVERY_CONFLICT,
                    "GitHub Webhook delivery identity conflicts with an existing fact");
        }
        return new GitHubPullRequestWebhookResult(
                GitHubWebhookDisposition.DUPLICATE, committed);
    }

    private ExternalObservation observation(
            AcceptGitHubPullRequestWebhookRequest request) {
        JsonNode root;
        try {
            root = objectMapper.readTree(request.payload());
        } catch (RuntimeException invalid) {
            throw failure(
                    GitHubWebhookErrorCode.PAYLOAD_INVALID,
                    "GitHub Webhook payload is invalid");
        }
        String action = requiredText(root, "action", 100).toLowerCase(Locale.ROOT);
        if (!SUPPORTED_ACTIONS.contains(action)) {
            throw failure(
                    GitHubWebhookErrorCode.EVENT_UNSUPPORTED,
                    "GitHub Pull Request Webhook action is unsupported");
        }
        JsonNode repository = root.path("repository");
        JsonNode pullRequest = root.path("pull_request");
        String repositoryId = requiredNumeric(repository, "id");
        String pullRequestId = requiredNumeric(pullRequest, "id");
        long number = positiveLong(pullRequest, "number");
        ExternalResultIdentity observedIdentity = new ExternalResultIdentity(
                request.expectedIdentity().connectionId(),
                ExternalObjectType.PULL_REQUEST,
                pullRequestId,
                repositoryId + ":pull-request:" + number);
        if (!repositoryId.equals(request.expectedRepositoryId().value())
                || !observedIdentity.equals(request.expectedIdentity())) {
            throw failure(
                    GitHubWebhookErrorCode.IDENTITY_MISMATCH,
                    "GitHub Webhook target does not match the routed Pull Request");
        }
        UtcTimestamp providerUpdatedAt;
        try {
            providerUpdatedAt = UtcTimestamp.parse(
                    requiredText(pullRequest, "updated_at", 100));
        } catch (RuntimeException invalid) {
            throw failure(
                    GitHubWebhookErrorCode.PAYLOAD_INVALID,
                    "GitHub Webhook Provider time is invalid");
        }
        if (providerUpdatedAt.compareTo(request.receivedAt()) > 0) {
            throw failure(
                    GitHubWebhookErrorCode.PAYLOAD_INVALID,
                    "GitHub Webhook Provider time is after receipt time");
        }
        ExternalObjectStatus status = status(pullRequest);
        String evidenceValue = "connection=" + request.expectedIdentity().connectionId()
                + "\ndelivery=" + request.deliveryId()
                + "\npayloadSha256=" + sha256(request.payload());
        return new ExternalObservation(
                ExternalObservationKey.derive(
                        request.expectedIdentity().connectionId(),
                        ExternalResultSource.WEBHOOK,
                        request.deliveryId()),
                request.actionId(),
                request.actionDigest(),
                observedIdentity,
                status,
                java.util.Optional.empty(),
                java.util.Optional.of(providerUpdatedAt),
                ExternalResultSource.WEBHOOK,
                ActionEvidenceReference.hashed(
                        "GITHUB_PULL_REQUEST_WEBHOOK", evidenceValue),
                request.receivedAt());
    }

    private static ExternalObjectStatus status(JsonNode pullRequest) {
        JsonNode merged = pullRequest.path("merged");
        if (merged.isBoolean() && merged.asBoolean()) {
            return ExternalObjectStatus.MERGED;
        }
        String state = requiredText(pullRequest, "state", 20).toLowerCase(Locale.ROOT);
        return switch (state) {
            case "open" -> ExternalObjectStatus.OPEN;
            case "closed" -> ExternalObjectStatus.CLOSED;
            default -> throw failure(
                    GitHubWebhookErrorCode.PAYLOAD_INVALID,
                    "GitHub Webhook Pull Request state is invalid");
        };
    }

    private static boolean verify(byte[] secret, byte[] payload, String signature) {
        byte[] requiredSecret = Objects.requireNonNull(secret, "secret");
        if (requiredSecret.length < 16 || requiredSecret.length > 8 * 1024) {
            throw failure(
                    GitHubWebhookErrorCode.SECRET_UNAVAILABLE,
                    "GitHub Webhook secret is unavailable");
        }
        byte[] supplied;
        if (signature == null || !signature.matches("sha256=[0-9a-f]{64}")) {
            return false;
        }
        try {
            supplied = HexFormat.of().parseHex(signature.substring("sha256=".length()));
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(requiredSecret, HMAC_ALGORITHM));
            return MessageDigest.isEqual(mac.doFinal(payload), supplied);
        } catch (IllegalArgumentException invalidSignature) {
            return false;
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", impossible);
        }
    }

    private static boolean sameDelivery(
            ExternalObservation left, ExternalObservation right) {
        return left.observationKey().equals(right.observationKey())
                && left.actionId().equals(right.actionId())
                && left.actionDigest().equals(right.actionDigest())
                && left.identity().equals(right.identity())
                && left.status() == right.status()
                && left.providerVersion().equals(right.providerVersion())
                && left.providerUpdatedAt().equals(right.providerUpdatedAt())
                && left.source() == right.source()
                && left.evidence().evidenceHash().equals(right.evidence().evidenceHash());
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String requiredNumeric(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() && !value.isTextual()) {
            throw failure(
                    GitHubWebhookErrorCode.PAYLOAD_INVALID,
                    "GitHub Webhook numeric identity is invalid");
        }
        String text = value.asText();
        if (!text.matches("[1-9][0-9]{0,19}")) {
            throw failure(
                    GitHubWebhookErrorCode.PAYLOAD_INVALID,
                    "GitHub Webhook numeric identity is invalid");
        }
        return text;
    }

    private static long positiveLong(JsonNode node, String field) {
        try {
            return Long.parseLong(requiredNumeric(node, field));
        } catch (NumberFormatException invalid) {
            throw failure(
                    GitHubWebhookErrorCode.PAYLOAD_INVALID,
                    "GitHub Webhook Pull Request number is invalid");
        }
    }

    private static String requiredText(JsonNode node, String field, int maximum) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()
                || value.asText().length() > maximum || value.asText().indexOf('\0') >= 0) {
            throw failure(
                    GitHubWebhookErrorCode.PAYLOAD_INVALID,
                    "GitHub Webhook text field is invalid");
        }
        return value.asText();
    }

    private static GitHubWebhookException failure(
            GitHubWebhookErrorCode code, String summary) {
        return new GitHubWebhookException(code, summary);
    }
}
