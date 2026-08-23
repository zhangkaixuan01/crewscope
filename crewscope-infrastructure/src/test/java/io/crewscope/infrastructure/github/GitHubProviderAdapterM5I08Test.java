package io.crewscope.infrastructure.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.crewscope.application.credential.CredentialDescriptor;
import io.crewscope.application.credential.CredentialReference;
import io.crewscope.application.credential.CredentialSecret;
import io.crewscope.application.credential.CredentialStatus;
import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.credential.CredentialSubject;
import io.crewscope.application.github.GitHubAccessRequest;
import io.crewscope.application.github.GitHubAuthenticationType;
import io.crewscope.application.github.GitHubCatalogResult;
import io.crewscope.application.github.GitHubConnectionProfile;
import io.crewscope.application.github.GitHubProviderErrorCode;
import io.crewscope.application.github.GitHubProviderException;
import io.crewscope.application.github.GitHubProviderRepository;
import io.crewscope.application.github.GitHubRateLimitSnapshot;
import io.crewscope.application.github.GitHubRepositoryCatalogEntry;
import io.crewscope.application.github.GitHubRepositoryPolicy;
import io.crewscope.application.github.PreflightGitHubRepositoryRequest;
import io.crewscope.application.github.SyncGitHubCatalogRequest;
import io.crewscope.application.github.VerifyGitHubConnectionRequest;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionGrantStatus;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ConnectionStatus;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderOwnerType;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** M5-I08 contract tests for GitHub identity, catalog, Preflight and safe failures. */
class GitHubProviderAdapterM5I08Test {

    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");
    private static final String TOKEN = "ghs_m5_i08_secret_token";

    @Test
    void verifiesTeamAppAndKeepsBlockedFactsOutsideTheDeliverableCatalog() throws Exception {
        try (GitHubStub stub = GitHubStub.start()) {
            Fixture fixture = Fixture.team(stub.baseUri());

            GitHubConnectionProfile profile = fixture.adapter.verifyConnection(
                    new VerifyGitHubConnectionRequest(
                            fixture.access(), GitHubAuthenticationType.APP_INSTALLATION,
                            fixture.policy(true)));
            GitHubCatalogResult catalog = fixture.adapter.synchronizeCatalog(
                    new SyncGitHubCatalogRequest(fixture.access(), fixture.policy(true)));

            assertEquals("4815", profile.externalAccountId());
            assertEquals(List.of("crewscope/repository-a"), catalog.deliverableRepositories()
                    .stream().map(GitHubRepositoryCatalogEntry::fullName).toList());
            assertEquals(4, catalog.blockedRepositoryCount());
            assertEquals(5, fixture.repository.repositories.size());
            assertEquals(4_993, catalog.rateLimit().remaining());
            assertTrue(stub.paths.contains(
                    "/installation/repositories?per_page=100&page=2"));
            assertTrue(stub.sawRequiredHeaders);

            var preflight = fixture.adapter.preflightRepository(
                    new PreflightGitHubRepositoryRequest(
                            fixture.access(), "101", new RepositoryBranchName("main"),
                            fixture.policy(true)));
            assertEquals("crewscope/repository-a", preflight.fullName());

            stub.defaultBranch = "trunk";
            GitHubProviderException drift = assertThrows(
                    GitHubProviderException.class,
                    () -> fixture.adapter.preflightRepository(
                            new PreflightGitHubRepositoryRequest(
                                    fixture.access(), "101", new RepositoryBranchName("main"),
                                    fixture.policy(true))));
            assertEquals(GitHubProviderErrorCode.DEFAULT_BRANCH_MISMATCH, drift.code());
            assertEquals("trunk", fixture.repository.repositories.get("101")
                    .defaultBranch().value());
        }
    }

    @Test
    void requiresExplicitBroadOauthPolicyAndRejectsCredentialSubjectSubstitution() throws Exception {
        try (GitHubStub stub = GitHubStub.start()) {
            Fixture oauth = Fixture.user(stub.baseUri(), true);
            GitHubProviderException policyDenied = assertThrows(
                    GitHubProviderException.class,
                    () -> oauth.adapter.verifyConnection(new VerifyGitHubConnectionRequest(
                            oauth.access(), GitHubAuthenticationType.OAUTH_USER,
                            oauth.policy(false))));
            assertEquals(GitHubProviderErrorCode.PERMISSION_DENIED, policyDenied.code());

            GitHubConnectionProfile verified = oauth.adapter.verifyConnection(
                    new VerifyGitHubConnectionRequest(
                            oauth.access(), GitHubAuthenticationType.OAUTH_USER,
                            oauth.policy(true)));
            assertEquals("2718", verified.externalAccountId());
            assertCode(GitHubProviderErrorCode.PERMISSION_DENIED,
                    () -> oauth.adapter.synchronizeCatalog(
                            new SyncGitHubCatalogRequest(oauth.access(), oauth.policy(false))));

            Fixture substituted = Fixture.user(stub.baseUri(), false);
            GitHubProviderException mismatch = assertThrows(
                    GitHubProviderException.class,
                    () -> substituted.adapter.verifyConnection(new VerifyGitHubConnectionRequest(
                            substituted.access(), GitHubAuthenticationType.OAUTH_USER,
                            substituted.policy(true))));
            assertEquals(GitHubProviderErrorCode.IDENTITY_MISMATCH, mismatch.code());
        }
    }

    @Test
    void revalidatesExactConnectionGrantAndCredentialVersionsBeforeEveryRequest() throws Exception {
        try (GitHubStub stub = GitHubStub.start()) {
            Fixture fixture = Fixture.team(stub.baseUri());
            GitHubAccessRequest staleConnection = new GitHubAccessRequest(
                    fixture.organizationId, fixture.connection.id(), 1,
                    fixture.grant.id(), fixture.grant.version(), fixture.owner,
                    fixture.access, fixture.actor, UUID.randomUUID());
            assertCode(GitHubProviderErrorCode.CONNECTION_UNAVAILABLE,
                    () -> fixture.adapter.verifyConnection(new VerifyGitHubConnectionRequest(
                            staleConnection, GitHubAuthenticationType.APP_INSTALLATION,
                            fixture.policy(true))));

            GitHubAccessRequest staleGrant = new GitHubAccessRequest(
                    fixture.organizationId, fixture.connection.id(), fixture.connection.version(),
                    fixture.grant.id(), 1, fixture.owner,
                    fixture.access, fixture.actor, UUID.randomUUID());
            assertCode(GitHubProviderErrorCode.GRANT_UNAVAILABLE,
                    () -> fixture.adapter.verifyConnection(new VerifyGitHubConnectionRequest(
                            staleGrant, GitHubAuthenticationType.APP_INSTALLATION,
                            fixture.policy(true))));

            fixture.credentialVersionAtResolve = 1;
            assertCode(GitHubProviderErrorCode.CREDENTIAL_UNAVAILABLE,
                    () -> fixture.adapter.verifyConnection(new VerifyGitHubConnectionRequest(
                            fixture.access(), GitHubAuthenticationType.APP_INSTALLATION,
                            fixture.policy(true))));
        }
    }

    @Test
    void rejectsElevatedPermissionsCrossOriginPaginationAndSensitiveProviderFailures()
            throws Exception {
        try (GitHubStub stub = GitHubStub.start()) {
            Fixture fixture = Fixture.team(stub.baseUri());
            stub.elevatedPermission = true;
            assertCode(GitHubProviderErrorCode.PERMISSION_DENIED,
                    () -> fixture.adapter.verifyConnection(new VerifyGitHubConnectionRequest(
                            fixture.access(), GitHubAuthenticationType.APP_INSTALLATION,
                            fixture.policy(true))));

            stub.elevatedPermission = false;
            fixture.adapter.verifyConnection(new VerifyGitHubConnectionRequest(
                    fixture.access(), GitHubAuthenticationType.APP_INSTALLATION,
                    fixture.policy(true)));
            stub.crossOriginNextPage = true;
            assertCode(GitHubProviderErrorCode.PROVIDER_UNAVAILABLE,
                    () -> fixture.adapter.synchronizeCatalog(
                            new SyncGitHubCatalogRequest(fixture.access(), fixture.policy(true))));

            stub.crossOriginNextPage = false;
            stub.installationStatus = 401;
            Fixture unauthorized = Fixture.team(stub.baseUri());
            GitHubProviderException failure = assertThrows(
                    GitHubProviderException.class,
                    () -> unauthorized.adapter.verifyConnection(
                            new VerifyGitHubConnectionRequest(
                                    unauthorized.access(),
                                    GitHubAuthenticationType.APP_INSTALLATION,
                                    unauthorized.policy(true))));
            String publicFailure = failure + " " + failure.getMessage();
            assertEquals(GitHubProviderErrorCode.AUTHENTICATION_REQUIRED, failure.code());
            assertFalse(publicFailure.contains(TOKEN));
            assertFalse(publicFailure.contains("internal.example.invalid"));
            assertFalse(publicFailure.contains(stub.baseUri().toString()));
        }
    }

    @Test
    void normalizesEveryReadFailureWithoutRetainingProviderPayloads() {
        Map<Integer, GitHubProviderErrorCode> expected = Map.of(
                401, GitHubProviderErrorCode.AUTHENTICATION_REQUIRED,
                403, GitHubProviderErrorCode.PERMISSION_DENIED,
                404, GitHubProviderErrorCode.RESOURCE_UNAVAILABLE,
                409, GitHubProviderErrorCode.CONFLICT,
                422, GitHubProviderErrorCode.VALIDATION_FAILED,
                429, GitHubProviderErrorCode.RATE_LIMITED,
                500, GitHubProviderErrorCode.PROVIDER_UNAVAILABLE,
                503, GitHubProviderErrorCode.PROVIDER_UNAVAILABLE);
        expected.forEach((status, code) -> assertEquals(
                code,
                GitHubErrorNormalizer.normalize(
                        status, HttpHeaders.of(Map.of(), (left, right) -> true)).code()));
        assertEquals(
                GitHubProviderErrorCode.RATE_LIMITED,
                GitHubErrorNormalizer.normalize(
                        403,
                        HttpHeaders.of(
                                Map.of("X-RateLimit-Remaining", List.of("0")),
                                (left, right) -> true))
                        .code());
    }

    private static void assertCode(GitHubProviderErrorCode expected, Runnable operation) {
        assertEquals(expected, assertThrows(GitHubProviderException.class, operation::run).code());
    }

    private static final class Fixture {
        private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
        private final PrincipalId actor = new PrincipalId(UUID.randomUUID());
        private final TeamId teamId = new TeamId(UUID.randomUUID());
        private final ProviderOwner owner;
        private final Connection connection;
        private final ConnectionGrant grant;
        private final ProviderAccessScope access;
        private final InMemoryGitHubRepository repository = new InMemoryGitHubRepository();
        private final GitHubProviderAdapter adapter;
        private long credentialVersionAtResolve;

        private Fixture(URI baseUri, boolean userOwner, boolean validUserSubject) {
            UtcTimestamp now = UtcTimestamp.from(NOW);
            owner = userOwner
                    ? new ProviderOwner(
                            organizationId, ProviderOwnerType.USER, actor.value(),
                            Optional.empty(), Optional.of(actor))
                    : new ProviderOwner(
                            organizationId, ProviderOwnerType.TEAM, teamId.value(),
                            Optional.of(teamId), Optional.empty());
            CredentialId credentialId = new CredentialId(UUID.randomUUID());
            connection = Connection.reconstitute(
                    new ConnectionId(UUID.randomUUID()), organizationId, owner,
                    GitHubConnectionGrantAuthorizer.CONNECTOR_KEY,
                    userOwner ? "2718" : "4815", credentialId, ConnectionStatus.ACTIVE,
                    Optional.empty(), Optional.empty(), 0, AuditMetadata.createdBy(actor, now));
            access = new ProviderAccessScope(
                    ProviderCapabilities.of(
                            "source.repository.catalog", "source.repository.read",
                            "source.repository.push", "source.pull-request.create"),
                    ProviderResourceScope.allResources());
            grant = ConnectionGrant.reconstitute(
                    new ConnectionGrantId(UUID.randomUUID()), organizationId, connection.id(),
                    owner, owner, access, now, Optional.empty(), ConnectionGrantStatus.ACTIVE,
                    Optional.empty(), 0, AuditMetadata.createdBy(actor, now));

            ConnectionRepository connections = mock(ConnectionRepository.class);
            when(connections.findById(organizationId, connection.id()))
                    .thenReturn(Optional.of(connection));
            ConnectionGrantRepository grants = mock(ConnectionGrantRepository.class);
            when(grants.findById(organizationId, grant.id())).thenReturn(Optional.of(grant));
            CredentialStore credentials = mock(CredentialStore.class);
            CredentialDescriptor described = descriptor(
                    credentialId,
                    userOwner
                            ? (validUserSubject
                                    ? CredentialSubject.principal(organizationId, actor)
                                    : CredentialSubject.team(organizationId, teamId))
                            : CredentialSubject.team(organizationId, teamId),
                    0,
                    now);
            when(credentials.describe(any(CredentialReference.class), any()))
                    .thenReturn(Optional.of(described));
            when(credentials.resolve(any(CredentialReference.class), any())).thenAnswer(ignored ->
                    Optional.of(new io.crewscope.application.credential.ResolvedCredential(
                            descriptor(
                                    credentialId, described.subject(), credentialVersionAtResolve, now),
                            CredentialSecret.utf8(TOKEN))));

            adapter = new GitHubProviderAdapter(
                    HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                    new ObjectMapper(), baseUri, Duration.ofSeconds(5), Duration.ofMinutes(5),
                    Duration.ofSeconds(30),
                    TimeProvider.from(Clock.fixed(NOW, ZoneOffset.UTC)),
                    connections, grants, credentials, repository, true);
        }

        static Fixture team(URI baseUri) {
            return new Fixture(baseUri, false, true);
        }

        static Fixture user(URI baseUri, boolean validSubject) {
            return new Fixture(baseUri, true, validSubject);
        }

        GitHubAccessRequest access() {
            return new GitHubAccessRequest(
                    organizationId, connection.id(), connection.version(), grant.id(),
                    grant.version(), owner, access, actor, UUID.randomUUID());
        }

        GitHubRepositoryPolicy policy(boolean broadOauth) {
            return new GitHubRepositoryPolicy(
                    Set.of(
                            "crewscope/repository-a", "crewscope/archived",
                            "crewscope/fork", "crewscope/read-only", "other/not-allowed"),
                    Set.of("crewscope"), false, false, broadOauth);
        }

        private CredentialDescriptor descriptor(
                CredentialId credentialId,
                CredentialSubject subject,
                long secretVersion,
                UtcTimestamp now) {
            return new CredentialDescriptor(
                    credentialId, subject, "github-m5-i08",
                    GitHubConnectionGrantAuthorizer.CONNECTOR_KEY,
                    Optional.of(connection.id().value()),
                    GitHubConnectionGrantAuthorizer.CREDENTIAL_TYPE,
                    Map.of(), CredentialStatus.ACTIVE, Optional.empty(), Optional.empty(),
                    Optional.empty(), "test-key", "AES-256-GCM", "1", actor, actor,
                    now, now, secretVersion, secretVersion);
        }
    }

    private static final class InMemoryGitHubRepository implements GitHubProviderRepository {
        private final Map<Long, GitHubConnectionProfile> profiles = new LinkedHashMap<>();
        private final Map<String, GitHubRepositoryCatalogEntry> repositories = new LinkedHashMap<>();
        private final List<GitHubRateLimitSnapshot> rates = new ArrayList<>();

        @Override
        public Optional<GitHubConnectionProfile> findProfile(
                OrganizationId ignored, ConnectionId connectionId, long connectionVersion) {
            return Optional.ofNullable(profiles.get(connectionVersion))
                    .filter(value -> value.connectionId().equals(connectionId));
        }

        @Override
        public GitHubConnectionProfile insertProfile(GitHubConnectionProfile profile) {
            profiles.put(profile.connectionVersion(), profile);
            return profile;
        }

        @Override
        public void synchronizeCatalog(
                GitHubConnectionProfile ignored,
                List<GitHubRepositoryCatalogEntry> entries,
                GitHubRateLimitSnapshot rateLimit) {
            entries.forEach(value -> repositories.put(value.externalRepositoryId(), value));
            rates.add(rateLimit);
        }

        @Override
        public void recordPreflight(
                GitHubConnectionProfile ignored,
                GitHubRepositoryCatalogEntry entry,
                GitHubRateLimitSnapshot rateLimit) {
            repositories.put(entry.externalRepositoryId(), entry);
            rates.add(rateLimit);
        }

        @Override
        public Optional<GitHubRepositoryCatalogEntry> findRepository(
                OrganizationId ignoredOrganization,
                ConnectionId ignoredConnection,
                String externalRepositoryId) {
            return Optional.ofNullable(repositories.get(externalRepositoryId));
        }

        @Override
        public List<GitHubRepositoryCatalogEntry> findDeliverableRepositories(
                OrganizationId ignoredOrganization, ConnectionId ignoredConnection) {
            return repositories.values().stream()
                    .filter(value -> value.status()
                            == io.crewscope.application.github.GitHubRepositoryStatus.DELIVERABLE)
                    .toList();
        }

        @Override
        public Optional<GitHubRateLimitSnapshot> findCurrentRateLimit(
                OrganizationId ignoredOrganization,
                ConnectionId ignoredConnection,
                String resource) {
            return rates.stream().filter(value -> value.resource().equals(resource))
                    .reduce((left, right) -> right);
        }
    }

    private static final class GitHubStub implements AutoCloseable {
        private final HttpServer server;
        private final List<String> paths = new CopyOnWriteArrayList<>();
        private volatile boolean sawRequiredHeaders;
        private volatile boolean elevatedPermission;
        private volatile boolean crossOriginNextPage;
        private volatile int installationStatus = 200;
        private volatile String defaultBranch = "main";

        private GitHubStub(HttpServer server) {
            this.server = server;
        }

        static GitHubStub start() throws IOException {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            GitHubStub stub = new GitHubStub(server);
            server.createContext("/", stub::handle);
            server.start();
            return stub;
        }

        URI baseUri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().toString();
            paths.add(path);
            sawRequiredHeaders = GitHubProviderAdapter.GITHUB_ACCEPT.equals(
                            exchange.getRequestHeaders().getFirst("Accept"))
                    && GitHubProviderAdapter.GITHUB_API_VERSION.equals(
                            exchange.getRequestHeaders().getFirst("X-GitHub-Api-Version"));
            headers(exchange);
            if (path.equals("/installation")) {
                if (installationStatus != 200) {
                    respond(exchange, installationStatus,
                            "{\"message\":\"" + TOKEN
                                    + " internal.example.invalid\"}");
                    return;
                }
                String elevated = elevatedPermission ? ",\"actions\":\"read\"" : "";
                respond(exchange, 200,
                        "{\"id\":4815,\"account\":{\"login\":\"crewscope\"},"
                                + "\"permissions\":{\"metadata\":\"read\","
                                + "\"contents\":\"write\",\"pull_requests\":\"write\""
                                + elevated + "}}");
                return;
            }
            if (path.equals("/user")) {
                exchange.getResponseHeaders().add("X-OAuth-Scopes", "repo");
                respond(exchange, 200, "{\"id\":2718,\"login\":\"zhangkaixuan\"}");
                return;
            }
            if (path.equals("/installation/repositories?per_page=100&page=1")) {
                String next = crossOriginNextPage
                        ? "https://internal.example.invalid/installation/repositories?per_page=100&page=2"
                        : baseUri() + "/installation/repositories?per_page=100&page=2";
                exchange.getResponseHeaders().add("Link", "<" + next + ">; rel=\"next\"");
                respond(exchange, 200, "{\"repositories\":["
                        + repository("101", "crewscope/repository-a", false, false, true)
                        + "," + repository("102", "crewscope/archived", true, false, true)
                        + "," + repository("103", "crewscope/fork", false, true, true)
                        + "]}");
                return;
            }
            if (path.equals("/installation/repositories?per_page=100&page=2")) {
                respond(exchange, 200, "{\"repositories\":["
                        + repository("104", "crewscope/read-only", false, false, false)
                        + "," + repository("105", "other/not-allowed", false, false, true)
                        + "]}");
                return;
            }
            if (path.equals("/repositories/101")) {
                respond(exchange, 200,
                        repository("101", "crewscope/repository-a", false, false, true));
                return;
            }
            if (path.startsWith("/user/repos")) {
                respond(exchange, 200, "[]");
                return;
            }
            respond(exchange, 404, "{\"message\":\"not found\"}");
        }

        private String repository(
                String id, String fullName, boolean archived, boolean fork, boolean push) {
            int separator = fullName.indexOf('/');
            return "{\"id\":" + id + ",\"full_name\":\"" + fullName
                    + "\",\"name\":\"" + fullName.substring(separator + 1)
                    + "\",\"owner\":{\"login\":\"" + fullName.substring(0, separator)
                    + "\"},\"default_branch\":\"" + defaultBranch
                    + "\",\"visibility\":\"public\",\"archived\":" + archived
                    + ",\"fork\":" + fork
                    + ",\"permissions\":{\"pull\":true,\"push\":" + push + "}}";
        }

        private static void headers(HttpExchange exchange) {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-RateLimit-Resource", "core");
            exchange.getResponseHeaders().add("X-RateLimit-Limit", "5000");
            exchange.getResponseHeaders().add("X-RateLimit-Remaining", "4993");
            exchange.getResponseHeaders().add("X-RateLimit-Used", "7");
            exchange.getResponseHeaders().add("X-RateLimit-Reset", "1800000000");
        }

        private static void respond(HttpExchange exchange, int status, String body)
                throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
