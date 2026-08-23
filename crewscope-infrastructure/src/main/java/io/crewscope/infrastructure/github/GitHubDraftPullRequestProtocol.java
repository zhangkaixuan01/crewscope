package io.crewscope.infrastructure.github;

import io.crewscope.application.github.GitHubDraftPullRequestErrorCode;
import io.crewscope.application.github.GitHubDraftPullRequestException;
import io.crewscope.application.github.GitHubDraftPullRequestOutcome;
import io.crewscope.application.github.GitHubDraftPullRequestResult;
import io.crewscope.application.github.GitHubHash;
import io.crewscope.application.github.GitHubProviderErrorCode;
import io.crewscope.application.github.GitHubProviderException;
import io.crewscope.application.github.GitHubPullRequestState;
import io.crewscope.domain.action.CreateDraftPullRequestActionParameters;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exact-query, remote-Head and unknown-write recovery protocol for one GitHub Draft PR. */
final class GitHubDraftPullRequestProtocol {

    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_CANDIDATES = 100;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI apiBaseUri;
    private final URI webBaseUri;
    private final Duration requestTimeout;
    private final TimeProvider timeProvider;

    GitHubDraftPullRequestProtocol(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            URI apiBaseUri,
            URI webBaseUri,
            Duration requestTimeout,
            TimeProvider timeProvider,
            boolean allowLoopbackHttp) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.apiBaseUri = requireOrigin(apiBaseUri, allowLoopbackHttp, "API");
        this.webBaseUri = requireOrigin(webBaseUri, false, "Web");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative()
                || requestTimeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("GitHub Draft PR timeout is invalid");
        }
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    GitHubDraftPullRequestResult ensure(
            CreateDraftPullRequestActionParameters action,
            String repositoryFullName,
            byte[] credential) {
        CreateDraftPullRequestActionParameters required = Objects.requireNonNull(action, "action");
        String repository = requireRepository(repositoryFullName);
        String token = credential(credential);

        Optional<PullRequestFact> existing = findCandidate(required, repository, token);
        if (existing.isPresent()) {
            return result(GitHubDraftPullRequestOutcome.ALREADY_PRESENT, required,
                    existing.orElseThrow());
        }
        requireRemoteHead(required, repository, token);
        HttpResult response;
        try {
            response = send(
                    repositoryPath(repository) + "/pulls",
                    "POST",
                    createPayload(required),
                    token);
        } catch (GitHubProviderException transportFailure) {
            if (transportFailure.code() != GitHubProviderErrorCode.PROVIDER_UNAVAILABLE) {
                throw map(transportFailure);
            }
            return reconcileUnknown(required, repository, token, transportFailure);
        }
        if (response.status() == 201) {
            try {
                PullRequestFact created = parsePullRequest(
                        responseJson(response), required, repository);
                return result(GitHubDraftPullRequestOutcome.CREATED, required, created);
            } catch (GitHubDraftPullRequestException malformedWriteResponse) {
                // GitHub may have committed the PR even when its response cannot be trusted.
                return reconcileUnknown(required, repository, token, malformedWriteResponse);
            }
        }
        if (response.status() == 422) {
            return reconcileRejected(required, repository, token, response);
        }
        if (response.status() >= 500) {
            return reconcileUnknown(
                    required,
                    repository,
                    token,
                    GitHubErrorNormalizer.normalize(response.status(), response.headers()));
        }
        throw map(GitHubErrorNormalizer.normalize(response.status(), response.headers()));
    }

    /** Queries exact Head/Base/content coordinates and never sends a write request. */
    Optional<GitHubDraftPullRequestResult> query(
            CreateDraftPullRequestActionParameters action,
            String repositoryFullName,
            byte[] credential) {
        CreateDraftPullRequestActionParameters required = Objects.requireNonNull(action, "action");
        String repository = requireRepository(repositoryFullName);
        String token = credential(credential);
        return findCandidate(required, repository, token)
                .map(candidate -> result(
                        GitHubDraftPullRequestOutcome.ALREADY_PRESENT, required, candidate));
    }

    private GitHubDraftPullRequestResult reconcileRejected(
            CreateDraftPullRequestActionParameters action,
            String repository,
            String token,
            HttpResult rejected) {
        Optional<PullRequestFact> candidate = findCandidate(action, repository, token);
        if (candidate.isPresent()) {
            return result(
                    GitHubDraftPullRequestOutcome.RECOVERED_AFTER_UNKNOWN,
                    action,
                    candidate.orElseThrow());
        }
        throw map(GitHubErrorNormalizer.normalize(rejected.status(), rejected.headers()));
    }

    private GitHubDraftPullRequestResult reconcileUnknown(
            CreateDraftPullRequestActionParameters action,
            String repository,
            String token,
            RuntimeException writeFailure) {
        try {
            Optional<PullRequestFact> reconciled = findCandidate(action, repository, token);
            if (reconciled.isPresent()) {
                return result(
                        GitHubDraftPullRequestOutcome.RECOVERED_AFTER_UNKNOWN,
                        action,
                        reconciled.orElseThrow());
            }
        } catch (GitHubDraftPullRequestException reconciliationFailure) {
            if (reconciliationFailure.code()
                    == GitHubDraftPullRequestErrorCode.PULL_REQUEST_CONFLICT) {
                throw reconciliationFailure;
            }
            writeFailure.addSuppressed(reconciliationFailure);
        }
        throw failure(
                GitHubDraftPullRequestErrorCode.UNKNOWN,
                "GitHub Draft PR outcome requires reconciliation",
                writeFailure);
    }

    private Optional<PullRequestFact> findCandidate(
            CreateDraftPullRequestActionParameters action,
            String repository,
            String token) {
        String owner = repository.substring(0, repository.indexOf('/'));
        String path = repositoryPath(repository)
                + "/pulls?state=all&head=" + query(owner + ":" + action.head().value())
                + "&base=" + query(action.base().value())
                + "&per_page=" + MAX_CANDIDATES;
        HttpResult response = send(path, "GET", new byte[0], token);
        requireStatus(response, 200);
        JsonNode root = responseJson(response);
        if (!root.isArray()) {
            throw providerUnavailable("GitHub Pull Request query response is invalid");
        }
        List<PullRequestFact> candidates = new ArrayList<>();
        for (JsonNode candidate : root) {
            candidates.add(parsePullRequest(candidate, action, repository));
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (candidates.size() != 1) {
            throw conflict("Multiple GitHub Pull Requests match the confirmed target");
        }
        return Optional.of(candidates.get(0));
    }

    private void requireRemoteHead(
            CreateDraftPullRequestActionParameters action,
            String repository,
            String token) {
        HttpResult response = send(
                repositoryPath(repository) + "/git/ref/heads/" + pathBranch(action.head()),
                "GET",
                new byte[0],
                token);
        requireStatus(response, 200);
        JsonNode root = responseJson(response);
        RepositoryCommitId current;
        try {
            current = new RepositoryCommitId(requiredText(root.path("object"), "sha", 64));
        } catch (RuntimeException invalid) {
            throw providerUnavailable("GitHub Branch Head response is invalid");
        }
        if (!current.equals(action.headSha())) {
            throw failure(
                    GitHubDraftPullRequestErrorCode.REMOTE_HEAD_CONFLICT,
                    "GitHub Draft PR Head changed after confirmation");
        }
    }

    private PullRequestFact parsePullRequest(
            JsonNode node,
            CreateDraftPullRequestActionParameters action,
            String repository) {
        try {
            String id = requiredNumeric(node, "id");
            long number = positiveLong(node, "number");
            String headRef = requiredText(node.path("head"), "ref", 255);
            String headSha = requiredText(node.path("head"), "sha", 64);
            String headRepositoryId = requiredNumeric(node.path("head").path("repo"), "id");
            String baseRef = requiredText(node.path("base"), "ref", 255);
            String baseRepositoryId = requiredNumeric(node.path("base").path("repo"), "id");
            String title = requiredText(node, "title", 256);
            String body = optionalText(node.path("body"), 65_536);
            boolean draft = requiredBoolean(node, "draft");
            UtcTimestamp updatedAt = UtcTimestamp.parse(requiredText(node, "updated_at", 100));
            if (updatedAt.compareTo(timeProvider.now()) > 0) {
                throw new IllegalArgumentException("future Provider timestamp");
            }
            URI webUrl = requireWebUrl(
                    requiredText(node, "html_url", 2_048), repository, number);
            GitHubPullRequestState state = pullRequestState(node);

            boolean exact = action.repositoryId().value().equals(headRepositoryId)
                    && action.repositoryId().value().equals(baseRepositoryId)
                    && action.head().value().equals(headRef)
                    && action.headSha().value().equals(headSha)
                    && action.base().value().equals(baseRef)
                    && action.title().equals(title)
                    && action.body().equals(body)
                    && draft;
            if (!exact) {
                throw conflict("Existing GitHub Pull Request conflicts with the confirmed action");
            }
            return new PullRequestFact(id, number, webUrl, state, updatedAt);
        } catch (GitHubDraftPullRequestException safeFailure) {
            throw safeFailure;
        } catch (RuntimeException invalid) {
            throw providerUnavailable("GitHub Pull Request response is invalid");
        }
    }

    private GitHubDraftPullRequestResult result(
            GitHubDraftPullRequestOutcome outcome,
            CreateDraftPullRequestActionParameters action,
            PullRequestFact fact) {
        return new GitHubDraftPullRequestResult(
                outcome,
                action.connectionId(),
                action.repositoryId(),
                fact.id(),
                fact.number(),
                fact.webUrl(),
                action.head(),
                action.base(),
                action.headSha(),
                GitHubHash.sha256(action.title()),
                GitHubHash.sha256(action.body()),
                true,
                fact.state(),
                fact.updatedAt());
    }

    private byte[] createPayload(CreateDraftPullRequestActionParameters action) {
        try {
            return objectMapper.writeValueAsBytes(java.util.Map.of(
                    "title", action.title(),
                    "head", action.head().value(),
                    "base", action.base().value(),
                    "body", action.body(),
                    "draft", true));
        } catch (RuntimeException invalid) {
            throw providerUnavailable("GitHub Draft PR request could not be encoded");
        }
    }

    private HttpResult send(String path, String method, byte[] payload, String token) {
        URI target = resolve(path);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                    .timeout(requestTimeout)
                    .header("Accept", GitHubProviderAdapter.GITHUB_ACCEPT)
                    .header("X-GitHub-Api-Version", GitHubProviderAdapter.GITHUB_API_VERSION)
                    .header("Authorization", "Bearer " + token);
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
            } else {
                builder.GET();
            }
            HttpResponse<InputStream> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (!target.equals(response.uri())) {
                throw providerUnavailable("GitHub redirects are not allowed");
            }
            try (InputStream body = response.body()) {
                byte[] bounded = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bounded.length > MAX_RESPONSE_BYTES) {
                    throw providerUnavailable("GitHub response exceeded its safety limit");
                }
                return new HttpResult(response.statusCode(), response.headers(), bounded);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw GitHubErrorNormalizer.transportFailure();
        } catch (IOException | IllegalArgumentException ignored) {
            throw GitHubErrorNormalizer.transportFailure();
        }
    }

    private JsonNode responseJson(HttpResult result) {
        try {
            return objectMapper.readTree(result.body());
        } catch (RuntimeException invalid) {
            throw providerUnavailable("GitHub response JSON is invalid");
        }
    }

    private void requireStatus(HttpResult response, int expected) {
        if (response.status() != expected) {
            throw map(GitHubErrorNormalizer.normalize(response.status(), response.headers()));
        }
    }

    private URI resolve(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//")) {
            throw providerUnavailable("GitHub request path is invalid");
        }
        URI result = apiBaseUri.resolve(path);
        if (!sameOrigin(apiBaseUri, result)
                || result.getUserInfo() != null
                || result.getRawFragment() != null) {
            throw providerUnavailable("GitHub request target is invalid");
        }
        return result;
    }

    private URI requireWebUrl(String value, String repository, long number) {
        URI actual = URI.create(value);
        URI expected = webBaseUri.resolve(repository + "/pull/" + number);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException("non-canonical Pull Request URL");
        }
        return actual;
    }

    private static GitHubPullRequestState pullRequestState(JsonNode node) {
        if (!node.path("merged_at").isMissingNode()
                && !node.path("merged_at").isNull()
                && !node.path("merged_at").asText().isBlank()) {
            return GitHubPullRequestState.MERGED;
        }
        return switch (requiredText(node, "state", 20).toLowerCase(Locale.ROOT)) {
            case "open" -> GitHubPullRequestState.OPEN;
            case "closed" -> GitHubPullRequestState.CLOSED;
            default -> throw new IllegalArgumentException("unsupported Pull Request state");
        };
    }

    private static URI requireOrigin(URI value, boolean allowLoopbackHttp, String name) {
        URI required = Objects.requireNonNull(value, name + "BaseUri");
        boolean secure = "https".equalsIgnoreCase(required.getScheme());
        boolean loopback = allowLoopbackHttp
                && "http".equalsIgnoreCase(required.getScheme())
                && ("127.0.0.1".equals(required.getHost())
                        || "localhost".equalsIgnoreCase(required.getHost())
                        || "::1".equals(required.getHost()));
        if ((!secure && !loopback)
                || required.getHost() == null
                || required.getUserInfo() != null
                || required.getRawQuery() != null
                || required.getRawFragment() != null
                || !(required.getPath().isEmpty() || "/".equals(required.getPath()))) {
            throw new IllegalArgumentException(
                    "GitHub Draft PR " + name + " base URI must be an allowed origin");
        }
        return URI.create(required.getScheme().toLowerCase(Locale.ROOT) + "://"
                + required.getHost().toLowerCase(Locale.ROOT)
                + (required.getPort() < 0 ? "" : ":" + required.getPort()) + "/");
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI value) {
        if (value.getPort() >= 0) {
            return value.getPort();
        }
        return "https".equalsIgnoreCase(value.getScheme()) ? 443 : 80;
    }

    private static String requireRepository(String value) {
        if (value == null
                || !value.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9_.-]{1,100}")) {
            throw failure(
                    GitHubDraftPullRequestErrorCode.AUTHORITY_STALE,
                    "GitHub repository name is invalid");
        }
        return value;
    }

    private static String credential(byte[] value) {
        byte[] required = Objects.requireNonNull(value, "credential");
        if (required.length == 0 || required.length > 8 * 1024) {
            throw failure(
                    GitHubDraftPullRequestErrorCode.AUTHORITY_STALE,
                    "GitHub credential is unavailable");
        }
        for (byte current : required) {
            int unsigned = Byte.toUnsignedInt(current);
            if (unsigned < 0x21 || unsigned > 0x7e) {
                throw failure(
                        GitHubDraftPullRequestErrorCode.AUTHORITY_STALE,
                        "GitHub credential is unavailable");
            }
        }
        return new String(required, StandardCharsets.US_ASCII);
    }

    private static String repositoryPath(String repository) {
        return "/repos/" + repository;
    }

    private static String pathBranch(RepositoryBranchName branch) {
        return java.util.Arrays.stream(branch.value().split("/"))
                .map(value -> URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(java.util.stream.Collectors.joining("/"));
    }

    private static String query(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String requiredNumeric(JsonNode node, String field) {
        String value = requiredScalar(node, field);
        if (!value.matches("[1-9][0-9]{0,19}")) {
            throw new IllegalArgumentException("invalid numeric identity");
        }
        return value;
    }

    private static long positiveLong(JsonNode node, String field) {
        return Long.parseLong(requiredNumeric(node, field));
    }

    private static String requiredScalar(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() && !value.isTextual()) {
            throw new IllegalArgumentException("missing scalar");
        }
        String text = value.asText();
        if (text.isBlank()) {
            throw new IllegalArgumentException("blank scalar");
        }
        return text;
    }

    private static String requiredText(JsonNode node, String field, int maximum) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()
                || value.asText().length() > maximum || value.asText().indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid text");
        }
        return value.asText();
    }

    private static boolean requiredBoolean(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isBoolean()) {
            throw new IllegalArgumentException("invalid boolean");
        }
        return value.asBoolean();
    }

    private static String optionalText(JsonNode value, int maximum) {
        if (value.isNull() || value.isMissingNode()) {
            return "";
        }
        if (!value.isTextual() || value.asText().length() > maximum
                || value.asText().indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid optional text");
        }
        return value.asText();
    }

    private static GitHubDraftPullRequestException map(GitHubProviderException failure) {
        GitHubDraftPullRequestErrorCode code = switch (failure.code()) {
            case AUTHENTICATION_REQUIRED -> GitHubDraftPullRequestErrorCode.AUTHENTICATION_REQUIRED;
            case PERMISSION_DENIED, REPOSITORY_BLOCKED ->
                    GitHubDraftPullRequestErrorCode.PERMISSION_DENIED;
            case RATE_LIMITED -> GitHubDraftPullRequestErrorCode.RATE_LIMITED;
            case RESOURCE_UNAVAILABLE, CONNECTION_UNAVAILABLE, GRANT_UNAVAILABLE,
                    CREDENTIAL_UNAVAILABLE -> GitHubDraftPullRequestErrorCode.RESOURCE_UNAVAILABLE;
            case VALIDATION_FAILED -> GitHubDraftPullRequestErrorCode.VALIDATION_FAILED;
            case CONFLICT, IDENTITY_MISMATCH, REPOSITORY_STALE, DEFAULT_BRANCH_MISMATCH ->
                    GitHubDraftPullRequestErrorCode.AUTHORITY_STALE;
            case PROVIDER_UNAVAILABLE -> GitHubDraftPullRequestErrorCode.PROVIDER_UNAVAILABLE;
        };
        return failure(code, failure.getMessage(), failure);
    }

    private static GitHubDraftPullRequestException conflict(String summary) {
        return failure(GitHubDraftPullRequestErrorCode.PULL_REQUEST_CONFLICT, summary);
    }

    private static GitHubDraftPullRequestException providerUnavailable(String summary) {
        return failure(GitHubDraftPullRequestErrorCode.PROVIDER_UNAVAILABLE, summary);
    }

    private static GitHubDraftPullRequestException failure(
            GitHubDraftPullRequestErrorCode code, String summary) {
        return new GitHubDraftPullRequestException(code, summary);
    }

    private static GitHubDraftPullRequestException failure(
            GitHubDraftPullRequestErrorCode code, String summary, Throwable cause) {
        return new GitHubDraftPullRequestException(code, summary, cause);
    }

    private record HttpResult(int status, HttpHeaders headers, byte[] body) {}

    private record PullRequestFact(
            String id,
            long number,
            URI webUrl,
            GitHubPullRequestState state,
            UtcTimestamp updatedAt) {}
}
