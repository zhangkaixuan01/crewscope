package io.crewscope.infrastructure.github;

import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.github.GitHubAccessRequest;
import io.crewscope.application.github.GitHubAuthenticationType;
import io.crewscope.application.github.GitHubCatalogResult;
import io.crewscope.application.github.GitHubConnectionProfile;
import io.crewscope.application.github.GitHubConnectionProfileStatus;
import io.crewscope.application.github.GitHubHash;
import io.crewscope.application.github.GitHubPermission;
import io.crewscope.application.github.GitHubProviderErrorCode;
import io.crewscope.application.github.GitHubProviderException;
import io.crewscope.application.github.GitHubProviderPort;
import io.crewscope.application.github.GitHubProviderRepository;
import io.crewscope.application.github.GitHubRateLimitSnapshot;
import io.crewscope.application.github.GitHubRepositoryCatalogEntry;
import io.crewscope.application.github.GitHubRepositoryPolicy;
import io.crewscope.application.github.GitHubRepositoryPreflightResult;
import io.crewscope.application.github.GitHubRepositoryStatus;
import io.crewscope.application.github.GitHubRepositoryVisibility;
import io.crewscope.application.github.PreflightGitHubRepositoryRequest;
import io.crewscope.application.github.SyncGitHubCatalogRequest;
import io.crewscope.application.github.VerifyGitHubConnectionRequest;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderExecutionIdentity;
import io.crewscope.domain.provider.ProviderOwnerType;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * GitHub read-side Provider adapter for identity verification, repository discovery and Preflight.
 * Push and Pull Request writes are intentionally excluded until the Action Worker stages.
 */
public final class GitHubProviderAdapter implements GitHubProviderPort {

    public static final String GITHUB_ACCEPT = "application/vnd.github+json";
    public static final String GITHUB_API_VERSION = "2022-11-28";
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_CATALOG_PAGES = 100;
    private static final String INSTALLATION_CATALOG =
            "/installation/repositories?per_page=100&page=1";
    private static final String USER_CATALOG =
            "/user/repos?affiliation=owner%2Ccollaborator%2Corganization_member&per_page=100&page=1";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI apiBaseUri;
    private final Duration requestTimeout;
    private final Duration catalogTimeToLive;
    private final TimeProvider timeProvider;
    private final GitHubProviderRepository repository;
    private final GitHubConnectionGrantAuthorizer authorizer;

    public GitHubProviderAdapter(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            URI apiBaseUri,
            Duration requestTimeout,
            Duration catalogTimeToLive,
            Duration credentialHandleTimeToLive,
            TimeProvider timeProvider,
            ConnectionRepository connectionRepository,
            ConnectionGrantRepository grantRepository,
            CredentialStore credentialStore,
            GitHubProviderRepository repository,
            boolean allowLoopbackHttp) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.apiBaseUri = requireBaseUri(apiBaseUri, allowLoopbackHttp);
        this.requestTimeout = boundedDuration(requestTimeout, "requestTimeout", Duration.ofMinutes(2));
        this.catalogTimeToLive = boundedDuration(
                catalogTimeToLive, "catalogTimeToLive", Duration.ofHours(24));
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.authorizer = new GitHubConnectionGrantAuthorizer(
                connectionRepository,
                grantRepository,
                credentialStore,
                timeProvider,
                credentialHandleTimeToLive);
    }

    @Override
    public GitHubConnectionProfile verifyConnection(VerifyGitHubConnectionRequest request) {
        VerifyGitHubConnectionRequest required = Objects.requireNonNull(request, "request");
        try (AuthorizedGitHubAccess access = authorizer.authorize(
                required.access(), "github:connection:verify")) {
            RemoteIdentity remote = verifyRemoteIdentity(
                    required.authenticationType(),
                    required.repositoryPolicy(),
                    access.credentialHandle());
            requireConnectionIdentity(required, access, remote);
            UtcTimestamp now = timeProvider.now();
            GitHubConnectionProfile candidate = new GitHubConnectionProfile(
                    stableId("profile", access.connection().id().toString()
                            + ":" + access.connection().version()),
                    access.connection().organizationId(), access.connection().id(),
                    access.connection().version(), access.connection().owner(),
                    required.authenticationType() == GitHubAuthenticationType.APP_INSTALLATION
                            ? ProviderExecutionIdentity.TEAM_SERVICE_ACCOUNT
                            : ProviderExecutionIdentity.DELEGATED_USER,
                    required.authenticationType(), remote.externalId(), remote.login(),
                    remote.permissions(), required.repositoryPolicy().allowlistHash(),
                    GitHubConnectionProfileStatus.ACTIVE, 0,
                    AuditMetadata.createdBy(required.access().actor(), now));
            Optional<GitHubConnectionProfile> existing = repository.findProfile(
                    candidate.organizationId(), candidate.connectionId(), candidate.connectionVersion());
            if (existing.isPresent()) {
                requireEquivalentProfile(existing.orElseThrow(), candidate);
                return existing.orElseThrow();
            }
            GitHubConnectionProfile committed = repository.insertProfile(candidate);
            requireEquivalentProfile(committed, candidate);
            return committed;
        }
    }

    @Override
    public GitHubCatalogResult synchronizeCatalog(SyncGitHubCatalogRequest request) {
        SyncGitHubCatalogRequest required = Objects.requireNonNull(request, "request");
        try (AuthorizedGitHubAccess access = authorizer.authorize(
                required.access(), "github:repository:catalog")) {
            GitHubConnectionProfile profile = requireProfile(required.access(), required.repositoryPolicy());
            CatalogDiscovery discovery = discoverCatalog(
                    profile, required.repositoryPolicy(), access.effectiveAccess(),
                    required.access().actor(), access.credentialHandle());
            repository.synchronizeCatalog(profile, discovery.entries(), discovery.rateLimit());
            List<GitHubRepositoryCatalogEntry> deliverable = discovery.entries().stream()
                    .filter(value -> value.status() == GitHubRepositoryStatus.DELIVERABLE)
                    .sorted(Comparator.comparing(GitHubRepositoryCatalogEntry::fullName))
                    .toList();
            return new GitHubCatalogResult(
                    profile,
                    deliverable,
                    discovery.entries().size() - deliverable.size(),
                    discovery.rateLimit());
        }
    }

    @Override
    public GitHubRepositoryPreflightResult preflightRepository(
            PreflightGitHubRepositoryRequest request) {
        PreflightGitHubRepositoryRequest required = Objects.requireNonNull(request, "request");
        try (AuthorizedGitHubAccess access = authorizer.authorize(
                required.access(), "github:repository:preflight")) {
            GitHubConnectionProfile profile = requireProfile(required.access(), required.repositoryPolicy());
            GitHubRepositoryCatalogEntry persisted = repository.findRepository(
                    required.access().organizationId(), required.access().connectionId(),
                    required.externalRepositoryId())
                    .orElseThrow(() -> failure(
                            GitHubProviderErrorCode.RESOURCE_UNAVAILABLE,
                            "GitHub repository is unavailable"));
            UtcTimestamp now = timeProvider.now();
            if (persisted.status() == GitHubRepositoryStatus.STALE
                    || persisted.connectionVersion() != profile.connectionVersion()
                    || persisted.externalIdentity() != profile.externalIdentity()) {
                throw failure(GitHubProviderErrorCode.REPOSITORY_STALE,
                        "GitHub repository catalog entry is stale");
            }
            HttpResult response = send(
                    "/repositories/" + persisted.externalRepositoryId(),
                    access.credentialHandle());
            requireSuccessful(response);
            GitHubRateLimitSnapshot rate = rateLimit(
                    profile, response.headers(), now, required.access().actor());
            GitHubRepositoryCatalogEntry current = repositoryEntry(
                    profile,
                    responseJson(response),
                    response.headers(),
                    required.repositoryPolicy(),
                    access.effectiveAccess(),
                    required.access().actor(),
                    now);
            if (!current.externalRepositoryId().equals(persisted.externalRepositoryId())) {
                throw failure(GitHubProviderErrorCode.IDENTITY_MISMATCH,
                        "GitHub repository identity changed");
            }
            repository.recordPreflight(profile, current, rate);
            if (current.status() != GitHubRepositoryStatus.DELIVERABLE) {
                throw failure(GitHubProviderErrorCode.REPOSITORY_BLOCKED,
                        "GitHub repository is blocked by current authority");
            }
            if (!current.defaultBranch().equals(required.expectedDefaultBranch())) {
                throw failure(GitHubProviderErrorCode.DEFAULT_BRANCH_MISMATCH,
                        "GitHub default branch changed");
            }
            return new GitHubRepositoryPreflightResult(
                    access.connection().id(), access.connection().version(),
                    access.grant().id(), access.grant().version(),
                    current.externalRepositoryId(), current.fullName(),
                    current.defaultBranch(), current.permissionsHash());
        }
    }

    private RemoteIdentity verifyRemoteIdentity(
            GitHubAuthenticationType authentication,
            GitHubRepositoryPolicy policy,
            GitHubCredentialHandle handle) {
        HttpResult response = send(
                authentication == GitHubAuthenticationType.APP_INSTALLATION
                        ? "/installation" : "/user",
                handle);
        requireSuccessful(response);
        JsonNode root = responseJson(response);
        if (authentication == GitHubAuthenticationType.APP_INSTALLATION) {
            Set<GitHubPermission> permissions = appPermissions(root.path("permissions"));
            requireMinimumPermissions(permissions);
            rejectElevatedPermissions(permissions);
            return new RemoteIdentity(
                    requiredScalar(root, "id"),
                    requiredText(root.path("account"), "login", 255),
                    permissions);
        }
        Set<String> scopes = headerValues(response.headers(), "X-OAuth-Scopes");
        if (!policy.allowBroadUserOauth() || !scopes.contains("repo")) {
            throw failure(GitHubProviderErrorCode.PERMISSION_DENIED,
                    "GitHub OAuth repository scope is not allowed by current policy");
        }
        return new RemoteIdentity(
                requiredScalar(root, "id"),
                requiredText(root, "login", 255),
                GitHubPermission.minimumDraftDelivery());
    }

    private CatalogDiscovery discoverCatalog(
            GitHubConnectionProfile profile,
            GitHubRepositoryPolicy policy,
            ProviderAccessScope access,
            io.crewscope.domain.shared.id.PrincipalId actor,
            GitHubCredentialHandle handle) {
        String path = profile.authenticationType() == GitHubAuthenticationType.APP_INSTALLATION
                ? INSTALLATION_CATALOG : USER_CATALOG;
        List<GitHubRepositoryCatalogEntry> entries = new ArrayList<>();
        Set<String> discoveredIds = new HashSet<>();
        GitHubRateLimitSnapshot rate = null;
        int pageCount = 0;
        UtcTimestamp now = timeProvider.now();
        while (path != null) {
            if (++pageCount > MAX_CATALOG_PAGES) {
                throw failure(GitHubProviderErrorCode.PROVIDER_UNAVAILABLE,
                        "GitHub catalog pagination exceeded its safety limit");
            }
            HttpResult response = send(path, handle);
            requireSuccessful(response);
            rate = rateLimit(profile, response.headers(), now, actor);
            JsonNode root = responseJson(response);
            JsonNode repositories = profile.authenticationType() == GitHubAuthenticationType.APP_INSTALLATION
                    ? root.path("repositories") : root;
            if (!repositories.isArray()) {
                throw failure(GitHubProviderErrorCode.PROVIDER_UNAVAILABLE,
                        "GitHub catalog response shape is invalid");
            }
            for (JsonNode item : repositories) {
                GitHubRepositoryCatalogEntry entry = repositoryEntry(
                        profile, item, response.headers(), policy, access, actor, now);
                if (!discoveredIds.add(entry.externalRepositoryId())) {
                    throw failure(GitHubProviderErrorCode.PROVIDER_UNAVAILABLE,
                            "GitHub catalog returned a duplicate repository identity");
                }
                entries.add(entry);
            }
            path = nextPage(response.headers(), path);
        }
        if (rate == null) {
            throw failure(GitHubProviderErrorCode.PROVIDER_UNAVAILABLE,
                    "GitHub catalog returned no rate-limit authority");
        }
        return new CatalogDiscovery(List.copyOf(entries), rate);
    }

    private GitHubRepositoryCatalogEntry repositoryEntry(
            GitHubConnectionProfile profile,
            JsonNode item,
            HttpHeaders headers,
            GitHubRepositoryPolicy policy,
            ProviderAccessScope access,
            io.crewscope.domain.shared.id.PrincipalId actor,
            UtcTimestamp now) {
        String externalId = requiredScalar(item, "id");
        String fullName = requiredText(item, "full_name", 511);
        String owner = optionalText(item.path("owner").path("login"));
        String name = optionalText(item.path("name"));
        if (owner.isBlank() || name.isBlank()) {
            int separator = fullName.indexOf('/');
            if (separator <= 0 || separator == fullName.length() - 1) {
                throw failure(GitHubProviderErrorCode.PROVIDER_UNAVAILABLE,
                        "GitHub repository identity shape is invalid");
            }
            owner = fullName.substring(0, separator);
            name = fullName.substring(separator + 1);
        }
        RepositoryBranchName defaultBranch;
        try {
            defaultBranch = new RepositoryBranchName(requiredText(item, "default_branch", 255));
        } catch (RuntimeException ignored) {
            throw failure(GitHubProviderErrorCode.PROVIDER_UNAVAILABLE,
                    "GitHub default branch shape is invalid");
        }
        GitHubRepositoryVisibility visibility = visibility(item);
        boolean archived = booleanValue(item.path("archived"));
        boolean fork = booleanValue(item.path("fork"));
        boolean pull = booleanValue(item.path("permissions").path("pull"));
        boolean push = booleanValue(item.path("permissions").path("push"));
        boolean createPullRequest = pull
                && profile.grantedPermissions().contains(GitHubPermission.PULL_REQUESTS_WRITE);
        String resourceKey = "github:repository:" + fullName.toLowerCase(Locale.ROOT);
        boolean permittedByGrant = permitsResource(access.resources(), resourceKey);
        boolean permittedByPolicy = policy.permits(fullName, owner, visibility);
        GitHubRepositoryStatus status = !archived && !fork && pull && push
                        && createPullRequest && permittedByGrant && permittedByPolicy
                ? GitHubRepositoryStatus.DELIVERABLE
                : GitHubRepositoryStatus.BLOCKED;
        String permissionHash = GitHubHash.sha256(
                "pull=" + pull + "\npush=" + push + "\npullRequests=" + createPullRequest);
        Optional<String> etagHash = headers.firstValue("ETag")
                .filter(value -> !value.isBlank())
                .map(GitHubHash::sha256);
        return new GitHubRepositoryCatalogEntry(
                stableId("repository", profile.connectionId() + ":" + externalId),
                profile.organizationId(), profile.connectionId(), profile.connectionVersion(),
                profile.externalIdentity(), externalId, owner, name, fullName, defaultBranch,
                visibility, archived, fork, pull, push, createPullRequest,
                permissionHash, etagHash, now,
                UtcTimestamp.from(now.value().plus(catalogTimeToLive)), status, 0,
                AuditMetadata.createdBy(actor, now));
    }

    private GitHubConnectionProfile requireProfile(
            GitHubAccessRequest access, GitHubRepositoryPolicy policy) {
        GitHubConnectionProfile profile = repository.findProfile(
                access.organizationId(), access.connectionId(), access.expectedConnectionVersion())
                .filter(value -> value.isCurrentFor(access.expectedConnectionVersion()))
                .orElseThrow(() -> failure(
                        GitHubProviderErrorCode.CONNECTION_UNAVAILABLE,
                        "GitHub Connection Profile is unavailable"));
        if (!profile.repositoryAllowlistHash().equals(policy.allowlistHash())) {
            throw failure(GitHubProviderErrorCode.CONFLICT,
                    "GitHub repository policy changed after connection verification");
        }
        if (profile.authenticationType() == GitHubAuthenticationType.OAUTH_USER
                && !policy.allowBroadUserOauth()) {
            throw failure(GitHubProviderErrorCode.PERMISSION_DENIED,
                    "GitHub OAuth repository scope is not allowed by current policy");
        }
        return profile;
    }

    private HttpResult send(String path, GitHubCredentialHandle handle) {
        URI target = resolvePath(path);
        return handle.useSecret(secret -> {
            String token = new String(secret, StandardCharsets.UTF_8);
            try {
                HttpRequest request = HttpRequest.newBuilder(target)
                        .timeout(requestTimeout)
                        .header("Accept", GITHUB_ACCEPT)
                        .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
                HttpResponse<InputStream> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofInputStream());
                if (!target.equals(response.uri())) {
                    throw failure(GitHubProviderErrorCode.PROVIDER_UNAVAILABLE,
                            "GitHub redirects are not allowed");
                }
                try (InputStream body = response.body()) {
                    byte[] boundedBody = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                    if (boundedBody.length > MAX_RESPONSE_BYTES) {
                        throw failure(GitHubProviderErrorCode.PROVIDER_UNAVAILABLE,
                                "GitHub response exceeded its safety limit");
                    }
                    return new HttpResult(response.statusCode(), response.headers(), boundedBody);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw GitHubErrorNormalizer.transportFailure();
            } catch (IOException | IllegalArgumentException ignored) {
                throw GitHubErrorNormalizer.transportFailure();
            }
        });
    }

    private JsonNode responseJson(HttpResult response) {
        try {
            return objectMapper.readTree(response.body());
        } catch (RuntimeException ignored) {
            throw failure(GitHubProviderErrorCode.PROVIDER_UNAVAILABLE,
                    "GitHub response JSON is invalid");
        }
    }

    private static void requireSuccessful(HttpResult response) {
        if (response.status() < 200 || response.status() >= 300) {
            throw GitHubErrorNormalizer.normalize(response.status(), response.headers());
        }
    }

    private GitHubRateLimitSnapshot rateLimit(
            GitHubConnectionProfile profile,
            HttpHeaders headers,
            UtcTimestamp observedAt,
            io.crewscope.domain.shared.id.PrincipalId actor) {
        try {
            String resource = requiredHeader(headers, "X-RateLimit-Resource");
            long limit = Long.parseLong(requiredHeader(headers, "X-RateLimit-Limit"));
            long remaining = Long.parseLong(requiredHeader(headers, "X-RateLimit-Remaining"));
            long used = Long.parseLong(requiredHeader(headers, "X-RateLimit-Used"));
            long reset = Long.parseLong(requiredHeader(headers, "X-RateLimit-Reset"));
            return new GitHubRateLimitSnapshot(
                    UUID.randomUUID(), profile.organizationId(), profile.connectionId(),
                    profile.connectionVersion(), resource, limit, remaining, used,
                    UtcTimestamp.from(Instant.ofEpochSecond(reset)),
                    observedAt, actor);
        } catch (RuntimeException ignored) {
            throw failure(GitHubProviderErrorCode.PROVIDER_UNAVAILABLE,
                    "GitHub rate-limit response is invalid");
        }
    }

    private String nextPage(HttpHeaders headers, String currentPath) {
        String link = headers.firstValue("Link").orElse("");
        for (String value : link.split(",")) {
            if (!value.contains("rel=\"next\"")) {
                continue;
            }
            int start = value.indexOf('<');
            int end = value.indexOf('>');
            if (start < 0 || end <= start) {
                throw failure(GitHubProviderErrorCode.PROVIDER_UNAVAILABLE,
                        "GitHub pagination header is invalid");
            }
            URI next;
            try {
                next = URI.create(value.substring(start + 1, end));
            } catch (IllegalArgumentException ignored) {
                throw failure(GitHubProviderErrorCode.PROVIDER_UNAVAILABLE,
                        "GitHub pagination URI is invalid");
            }
            requireSameEndpoint(next);
            String nextPath = next.getRawPath()
                    + (next.getRawQuery() == null ? "" : "?" + next.getRawQuery());
            if (!catalogPath(next.getRawPath()) || nextPath.equals(currentPath)) {
                throw failure(GitHubProviderErrorCode.PROVIDER_UNAVAILABLE,
                        "GitHub pagination target is invalid");
            }
            return nextPath;
        }
        return null;
    }

    private URI resolvePath(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//")) {
            throw failure(GitHubProviderErrorCode.VALIDATION_FAILED,
                    "GitHub request path is invalid");
        }
        URI resolved = apiBaseUri.resolve(path);
        requireSameEndpoint(resolved);
        return resolved;
    }

    private void requireSameEndpoint(URI target) {
        int basePort = effectivePort(apiBaseUri);
        int targetPort = effectivePort(target);
        if (!apiBaseUri.getScheme().equalsIgnoreCase(target.getScheme())
                || !apiBaseUri.getHost().equalsIgnoreCase(target.getHost())
                || basePort != targetPort
                || target.getUserInfo() != null
                || target.getFragment() != null) {
            throw failure(GitHubProviderErrorCode.PROVIDER_UNAVAILABLE,
                    "GitHub redirect or pagination target is not allowed");
        }
    }

    private static Set<GitHubPermission> appPermissions(JsonNode permissions) {
        EnumSet<GitHubPermission> result = EnumSet.noneOf(GitHubPermission.class);
        permission(permissions, "metadata", "read", result, GitHubPermission.REPOSITORY_METADATA_READ);
        String contents = optionalText(permissions.path("contents"));
        if (contents.equals("read") || contents.equals("write")) {
            result.add(GitHubPermission.CONTENTS_READ);
        }
        if (contents.equals("write")) {
            result.add(GitHubPermission.CONTENTS_WRITE);
        }
        permission(permissions, "pull_requests", "write", result, GitHubPermission.PULL_REQUESTS_WRITE);
        elevated(permissions, "administration", result, GitHubPermission.ADMINISTRATION);
        elevated(permissions, "actions", result, GitHubPermission.ACTIONS);
        elevated(permissions, "secrets", result, GitHubPermission.SECRETS);
        elevated(permissions, "members", result, GitHubPermission.MEMBERS);
        elevated(permissions, "hooks", result, GitHubPermission.WEBHOOKS_WRITE);
        elevated(permissions, "webhooks", result, GitHubPermission.WEBHOOKS_WRITE);
        return Set.copyOf(result);
    }

    private static void permission(
            JsonNode source,
            String field,
            String required,
            Set<GitHubPermission> target,
            GitHubPermission permission) {
        if (optionalText(source.path(field)).equals(required)) {
            target.add(permission);
        }
    }

    private static void elevated(
            JsonNode source,
            String field,
            Set<GitHubPermission> target,
            GitHubPermission permission) {
        String value = optionalText(source.path(field));
        if (!value.isBlank() && !value.equals("none")) {
            target.add(permission);
        }
    }

    private static void requireMinimumPermissions(Set<GitHubPermission> permissions) {
        if (!permissions.containsAll(GitHubPermission.minimumDraftDelivery())) {
            throw failure(GitHubProviderErrorCode.PERMISSION_DENIED,
                    "GitHub App permissions are incomplete");
        }
    }

    private static void rejectElevatedPermissions(Set<GitHubPermission> permissions) {
        if (permissions.stream().anyMatch(
                GitHubPermission.forbiddenElevatedPermissions()::contains)) {
            throw failure(GitHubProviderErrorCode.PERMISSION_DENIED,
                    "GitHub App requests permissions outside the delivery minimum");
        }
    }

    private static void requireConnectionIdentity(
            VerifyGitHubConnectionRequest request,
            AuthorizedGitHubAccess access,
            RemoteIdentity remote) {
        ProviderOwnerType expectedOwner = request.authenticationType()
                == GitHubAuthenticationType.APP_INSTALLATION
                ? ProviderOwnerType.TEAM : ProviderOwnerType.USER;
        if (access.connection().owner().type() != expectedOwner
                || !access.connection().externalAccountReference().equals(remote.externalId())) {
            throw failure(GitHubProviderErrorCode.IDENTITY_MISMATCH,
                    "GitHub remote identity does not match the Connection");
        }
    }

    private static void requireEquivalentProfile(
            GitHubConnectionProfile current, GitHubConnectionProfile candidate) {
        if (!current.id().equals(candidate.id())
                || !current.connectionOwner().equals(candidate.connectionOwner())
                || current.externalIdentity() != candidate.externalIdentity()
                || current.authenticationType() != candidate.authenticationType()
                || !current.externalAccountId().equals(candidate.externalAccountId())
                || !current.externalAccountLogin().equals(candidate.externalAccountLogin())
                || !current.grantedPermissions().equals(candidate.grantedPermissions())
                || !current.repositoryAllowlistHash().equals(candidate.repositoryAllowlistHash())
                || current.status() != GitHubConnectionProfileStatus.ACTIVE) {
            throw failure(GitHubProviderErrorCode.CONFLICT,
                    "GitHub Connection Profile conflicts with current remote authority");
        }
    }

    private static GitHubRepositoryVisibility visibility(JsonNode item) {
        String value = optionalText(item.path("visibility")).toUpperCase(Locale.ROOT);
        if (value.isBlank()) {
            return booleanValue(item.path("private"))
                    ? GitHubRepositoryVisibility.PRIVATE : GitHubRepositoryVisibility.PUBLIC;
        }
        try {
            return GitHubRepositoryVisibility.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            throw failure(GitHubProviderErrorCode.PROVIDER_UNAVAILABLE,
                    "GitHub repository visibility is invalid");
        }
    }

    private static boolean permitsResource(ProviderResourceScope scope, String resource) {
        return scope.unrestricted() || scope.resources().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(resource::equals);
    }

    private static Set<String> headerValues(HttpHeaders headers, String name) {
        Set<String> result = new HashSet<>();
        for (String header : headers.allValues(name)) {
            for (String item : header.split(",")) {
                if (!item.isBlank()) {
                    result.add(item.strip().toLowerCase(Locale.ROOT));
                }
            }
        }
        return Set.copyOf(result);
    }

    private static String requiredHeader(HttpHeaders headers, String name) {
        return headers.firstValue(name)
                .filter(value -> !value.isBlank())
                .orElseThrow();
    }

    private static String requiredScalar(JsonNode source, String field) {
        JsonNode value = source.path(field);
        String text = optionalText(value).strip();
        if (text.isEmpty() || !text.matches("[0-9]{1,100}")) {
            throw failure(GitHubProviderErrorCode.PROVIDER_UNAVAILABLE,
                    "GitHub numeric identity is invalid");
        }
        return text;
    }

    private static String requiredText(JsonNode source, String field, int maximum) {
        String value = optionalText(source.path(field)).strip();
        if (value.isEmpty() || value.indexOf('\0') >= 0 || value.length() > maximum) {
            throw failure(GitHubProviderErrorCode.PROVIDER_UNAVAILABLE,
                    "GitHub text response is invalid");
        }
        return value;
    }

    private static boolean catalogPath(String path) {
        return "/installation/repositories".equals(path) || "/user/repos".equals(path);
    }

    private static String optionalText(JsonNode value) {
        if (value.isString()) {
            return value.stringValue();
        }
        if (value.isNumber()) {
            return value.numberValue().toString();
        }
        return "";
    }

    private static boolean booleanValue(JsonNode value) {
        return value.isBoolean() && value.booleanValue();
    }

    private static URI requireBaseUri(URI value, boolean allowLoopbackHttp) {
        URI uri = Objects.requireNonNull(value, "apiBaseUri").normalize();
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean loopback = "http".equalsIgnoreCase(uri.getScheme())
                && allowLoopbackHttp
                && ("127.0.0.1".equals(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost()));
        if ((!https && !loopback) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("GitHub API base URI must be an origin-only HTTPS URI");
        }
        return URI.create(uri.getScheme() + "://" + uri.getAuthority() + "/");
    }

    private static Duration boundedDuration(Duration value, String field, Duration maximum) {
        Duration duration = Objects.requireNonNull(value, field);
        if (duration.isZero() || duration.isNegative() || duration.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(field + " is outside its allowed range");
        }
        return duration;
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static UUID stableId(String kind, String value) {
        return UUID.nameUUIDFromBytes(
                ("crewscope:github:" + kind + ":v1:" + value)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static GitHubProviderException failure(
            GitHubProviderErrorCode code, String summary) {
        return new GitHubProviderException(code, summary);
    }

    private record HttpResult(int status, HttpHeaders headers, byte[] body) {
        private HttpResult {
            Objects.requireNonNull(headers, "headers");
            body = Objects.requireNonNull(body, "body").clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    private record RemoteIdentity(
            String externalId, String login, Set<GitHubPermission> permissions) {
        private RemoteIdentity {
            permissions = Set.copyOf(permissions);
        }
    }

    private record CatalogDiscovery(
            List<GitHubRepositoryCatalogEntry> entries,
            GitHubRateLimitSnapshot rateLimit) {
        private CatalogDiscovery {
            entries = List.copyOf(entries);
        }
    }
}
