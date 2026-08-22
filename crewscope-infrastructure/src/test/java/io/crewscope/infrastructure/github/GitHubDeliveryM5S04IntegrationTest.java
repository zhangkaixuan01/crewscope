package io.crewscope.infrastructure.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Executable M5-S04 proof for GitHub identity, credential and delivery boundaries. */
@Tag("integration")
class GitHubDeliveryM5S04IntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    private static final String APP_TOKEN = "ghs_m5s04_installation_secret";
    private static final String OAUTH_TOKEN = "github_pat_m5s04_user_secret";

    @TempDir Path temporaryDirectory;

    @Test
    void isolatesTeamAppAndUserOauthIdentitiesWhileDiscoveringOnlyDeliverableRepositories()
            throws Exception {
        GitHubConnectionShape teamApp = GitHubConnectionShape.teamApp(
                "team-platform",
                CredentialSubject.team("team-platform"),
                "installation-4815",
                Set.of("crewscope/repository-a", "crewscope/archived", "crewscope/fork"));
        GitHubConnectionShape userOauth = GitHubConnectionShape.userOauth(
                "user-zhang",
                CredentialSubject.principal("user-zhang"),
                "github-user-2718",
                Set.of("crewscope/repository-a", "zhang/personal-tool"));

        assertEquals(ExternalIdentity.TEAM_SERVICE_ACCOUNT, teamApp.externalIdentity());
        assertEquals(CredentialSubjectType.TEAM, teamApp.credentialSubject().type());
        assertEquals(ExternalIdentity.DELEGATED_USER, userOauth.externalIdentity());
        assertEquals(CredentialSubjectType.PRINCIPAL, userOauth.credentialSubject().type());
        assertThrows(IllegalArgumentException.class, () -> GitHubConnectionShape.userOauth(
                "user-zhang",
                CredentialSubject.team("team-platform"),
                "github-user-2718",
                Set.of("crewscope/repository-a")));

        try (GitHubApiStub github = GitHubApiStub.start(APP_TOKEN, OAUTH_TOKEN)) {
            RepositoryCatalogProbe catalog = new RepositoryCatalogProbe(github.baseUri());
            CatalogResult appCatalog = catalog.discover(teamApp, APP_TOKEN);
            CatalogResult oauthCatalog = catalog.discover(userOauth, OAUTH_TOKEN);

            assertEquals(List.of("crewscope/repository-a"), names(appCatalog.resources()));
            assertEquals(List.of("crewscope/repository-a", "zhang/personal-tool"),
                    names(oauthCatalog.resources()));
            assertEquals(new RateLimitSnapshot("core", 5_000, 4_993, 7,
                    Instant.ofEpochSecond(1_800_000_000L)), appCatalog.rateLimit());
            assertTrue(github.requestPaths().contains("/installation/repositories?per_page=2&page=2"));
            assertTrue(github.requestPaths().contains("/user/repos?affiliation=owner%2Ccollaborator%2Corganization_member&per_page=2&page=2"));
            assertEquals(4, github.authorizedRequests());

            String publicEvidence = appCatalog + " " + oauthCatalog + " " + github.safeSummary();
            assertFalse(publicEvidence.contains(APP_TOKEN));
            assertFalse(publicEvidence.contains(OAUTH_TOKEN));
            assertFalse(publicEvidence.contains(github.baseUri().toString()));
        }
    }

    @Test
    void injectsHttpCredentialsThroughOneShotAskPassAndCleansEveryTemporarySecret()
            throws Exception {
        Assumptions.assumeTrue(commandSucceeds("git", "--version"), "Git is required");
        String installationToken = "ghs_action_window_only_m5s04";
        Path repository = temporaryDirectory.resolve("credential-repository");
        runRequired(temporaryDirectory, "git", "init", repository.toString());

        Path askPassPath;
        Path tokenPath;
        CredentialUseProof proof;
        try (AskPassCredentialSession session =
                AskPassCredentialSession.open(temporaryDirectory.resolve("askpass"), installationToken)) {
            askPassPath = session.askPassPath();
            tokenPath = session.tokenPath();
            List<String> argv = List.of("git", "-c", "credential.helper=", "credential", "fill");
            assertTrue(argv.stream().noneMatch(value -> value.contains(installationToken)));
            assertTrue(session.environment().values().stream()
                    .noneMatch(value -> value.contains(installationToken)));
            assertFalse(Files.readString(askPassPath).contains(installationToken));

            proof = session.probeGitCredential(argv, installationToken);
            assertEquals("x-access-token", proof.username());
            assertEquals("github.example", proof.host());
            assertTrue(proof.passwordMatched());
            assertFalse(proof.toString().contains(installationToken));

            String localConfig = runRequired(repository, "git", "config", "--local", "--list");
            assertFalse(localConfig.contains(installationToken));
            assertFalse(session.safeAuditSummary().contains(installationToken));
            assertFalse(session.agentVisibleInput().contains(installationToken));
        }

        assertFalse(Files.exists(askPassPath));
        assertFalse(Files.exists(tokenPath));
        assertTrue(proof.secretBufferCleared());
    }

    @Test
    void usesManagedMirrorAndRemoteHeadChecksToMakePushIdempotent() throws Exception {
        Assumptions.assumeTrue(commandSucceeds("git", "--version"), "Git is required");
        GitFixture fixture = GitFixture.create(temporaryDirectory.resolve("push-fixture"));
        PushProbe push = new PushProbe(fixture.mirror(), fixture.remote());

        PushResult first = push.push(
                new PushOperation("crewscope/task-101", fixture.deliveryHead(), Optional.empty()),
                false);
        PushResult retry = push.push(
                new PushOperation("crewscope/task-101", fixture.deliveryHead(), Optional.empty()),
                false);
        assertEquals(PushOutcome.PUSHED, first.outcome());
        assertEquals(PushOutcome.ALREADY_PRESENT, retry.outcome());
        assertEquals(1, push.executedPushes());

        PushOperation responseLost =
                new PushOperation("crewscope/task-102", fixture.deliveryHead(), Optional.empty());
        UnknownDeliveryException unknown = assertThrows(
                UnknownDeliveryException.class, () -> push.push(responseLost, true));
        assertFalse(unknown.getMessage().contains(fixture.remote().toString()));
        PushResult reconciled = push.push(responseLost, false);
        assertEquals(PushOutcome.ALREADY_PRESENT, reconciled.outcome());
        assertEquals(2, push.executedPushes());

        GitHubDeliveryException staleExpectation = assertThrows(
                GitHubDeliveryException.class,
                () -> push.push(new PushOperation(
                        "crewscope/task-102", fixture.baselineHead(), Optional.empty()), false));
        assertEquals(GitHubErrorCode.REMOTE_HEAD_CONFLICT, staleExpectation.code());

        GitHubDeliveryException nonFastForward = assertThrows(
                GitHubDeliveryException.class,
                () -> push.push(new PushOperation(
                        "crewscope/task-102",
                        fixture.baselineHead(),
                        Optional.of(fixture.deliveryHead())),
                        false));
        assertEquals(GitHubErrorCode.NON_FAST_FORWARD, nonFastForward.code());
        assertEquals(fixture.deliveryHead(), fixture.remoteHead("crewscope/task-102"));
    }

    @Test
    void reconcilesLostDraftPrResponsesAndNeverCreatesASecondPullRequest() throws Exception {
        try (GitHubApiStub github = GitHubApiStub.start(APP_TOKEN, OAUTH_TOKEN)) {
            DraftPullRequestProbe client = new DraftPullRequestProbe(github.baseUri(), APP_TOKEN);
            DraftPrOperation operation = new DraftPrOperation(
                    "crewscope/repository-a",
                    "crewscope/task-101",
                    "main",
                    "a".repeat(40),
                    "Fix task 101",
                    "CrewScope delivery evidence");

            github.loseNextDraftResponse();
            DraftPrResult reconciled = client.ensureDraft(operation);
            DraftPrResult retry = client.ensureDraft(operation);
            assertEquals(DraftPrOutcome.RECONCILED_AFTER_UNKNOWN, reconciled.outcome());
            assertEquals(DraftPrOutcome.ALREADY_PRESENT, retry.outcome());
            assertEquals(reconciled.number(), retry.number());
            assertEquals(1, github.createdPullRequests());

            GitHubDeliveryException conflict = assertThrows(
                    GitHubDeliveryException.class,
                    () -> client.ensureDraft(new DraftPrOperation(
                            operation.repository(),
                            operation.headBranch(),
                            operation.baseBranch(),
                            operation.headSha(),
                            "Changed title after confirmation",
                            operation.body())));
            assertEquals(GitHubErrorCode.PULL_REQUEST_CONFLICT, conflict.code());
            assertEquals(1, github.createdPullRequests());

            String publicResult = reconciled + " " + retry + " " + conflict;
            assertFalse(publicResult.contains(APP_TOKEN));
            assertFalse(publicResult.contains(github.baseUri().toString()));
        }
    }

    @Test
    void freezesMinimumPermissionsSafeErrorsAndRateLimitFacts() {
        assertEquals(
                EnumSet.of(
                        GitHubPermission.REPOSITORY_METADATA_READ,
                        GitHubPermission.CONTENTS_READ,
                        GitHubPermission.CONTENTS_WRITE,
                        GitHubPermission.PULL_REQUESTS_WRITE),
                GitHubPermission.minimumDraftDelivery());
        assertFalse(GitHubPermission.minimumDraftDelivery().contains(GitHubPermission.ADMINISTRATION));
        assertFalse(GitHubPermission.minimumDraftDelivery().contains(GitHubPermission.ACTIONS));
        assertFalse(GitHubPermission.minimumDraftDelivery().contains(GitHubPermission.SECRETS));
        assertFalse(GitHubPermission.minimumDraftDelivery().contains(GitHubPermission.MEMBERS));
        assertFalse(GitHubPermission.minimumDraftDelivery().contains(GitHubPermission.WEBHOOKS_WRITE));

        assertError(401, Map.of(), GitHubErrorCode.AUTHENTICATION_REQUIRED);
        assertError(403, Map.of(), GitHubErrorCode.PERMISSION_DENIED);
        assertError(403, Map.of("X-RateLimit-Remaining", "0"), GitHubErrorCode.RATE_LIMITED);
        assertError(404, Map.of(), GitHubErrorCode.RESOURCE_UNAVAILABLE);
        assertError(409, Map.of(), GitHubErrorCode.CONFLICT);
        assertError(422, Map.of(), GitHubErrorCode.VALIDATION_FAILED);
        assertError(429, Map.of("Retry-After", "60"), GitHubErrorCode.RATE_LIMITED);
        assertError(500, Map.of(), GitHubErrorCode.PROVIDER_UNAVAILABLE);
        assertError(503, Map.of(), GitHubErrorCode.PROVIDER_UNAVAILABLE);
    }

    private static void assertError(
            int status, Map<String, String> headers, GitHubErrorCode expected) {
        String rawBody = "{\"message\":\"provider-secret internal.example.invalid\"}";
        GitHubDeliveryException failure = GitHubErrorNormalizer.normalize(status, headers, rawBody);
        assertEquals(expected, failure.code());
        assertFalse(failure.getMessage().contains("provider-secret"));
        assertFalse(failure.getMessage().contains("internal.example.invalid"));
        assertFalse(failure.toString().contains(rawBody));
    }

    private static List<String> names(List<RepositoryResource> resources) {
        return resources.stream().map(RepositoryResource::fullName).toList();
    }

    private enum ConnectionKind {
        GITHUB_APP,
        OAUTH
    }

    private enum ConnectionOwnerType {
        TEAM,
        USER
    }

    private enum ExternalIdentity {
        DELEGATED_USER,
        TEAM_SERVICE_ACCOUNT
    }

    private enum CredentialSubjectType {
        TEAM,
        ORGANIZATION,
        PRINCIPAL
    }

    private record CredentialSubject(CredentialSubjectType type, String subjectId) {

        private CredentialSubject {
            Objects.requireNonNull(type, "type");
            requireText(subjectId, "subjectId");
        }

        static CredentialSubject team(String teamId) {
            return new CredentialSubject(CredentialSubjectType.TEAM, teamId);
        }

        static CredentialSubject principal(String principalId) {
            return new CredentialSubject(CredentialSubjectType.PRINCIPAL, principalId);
        }
    }

    private record GitHubConnectionShape(
            ConnectionKind kind,
            ConnectionOwnerType ownerType,
            String ownerId,
            CredentialSubject credentialSubject,
            ExternalIdentity externalIdentity,
            String externalIdentityId,
            Set<String> repositoryAllowlist,
            Set<GitHubPermission> permissions) {

        private GitHubConnectionShape {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(ownerType, "ownerType");
            requireText(ownerId, "ownerId");
            Objects.requireNonNull(credentialSubject, "credentialSubject");
            Objects.requireNonNull(externalIdentity, "externalIdentity");
            requireText(externalIdentityId, "externalIdentityId");
            repositoryAllowlist = Set.copyOf(repositoryAllowlist);
            permissions = Set.copyOf(permissions);
            if (!permissions.containsAll(GitHubPermission.minimumDraftDelivery())) {
                throw new IllegalArgumentException("GitHub delivery permission set is incomplete");
            }
            if (kind == ConnectionKind.GITHUB_APP) {
                if (ownerType != ConnectionOwnerType.TEAM
                        || externalIdentity != ExternalIdentity.TEAM_SERVICE_ACCOUNT
                        || (credentialSubject.type() != CredentialSubjectType.TEAM
                                && credentialSubject.type() != CredentialSubjectType.ORGANIZATION)) {
                    throw new IllegalArgumentException("GitHub App identity shape is invalid");
                }
            } else if (ownerType != ConnectionOwnerType.USER
                    || externalIdentity != ExternalIdentity.DELEGATED_USER
                    || credentialSubject.type() != CredentialSubjectType.PRINCIPAL
                    || !ownerId.equals(credentialSubject.subjectId())) {
                throw new IllegalArgumentException("GitHub OAuth identity shape is invalid");
            }
        }

        static GitHubConnectionShape teamApp(
                String ownerId,
                CredentialSubject subject,
                String installationId,
                Set<String> repositories) {
            return new GitHubConnectionShape(
                    ConnectionKind.GITHUB_APP,
                    ConnectionOwnerType.TEAM,
                    ownerId,
                    subject,
                    ExternalIdentity.TEAM_SERVICE_ACCOUNT,
                    installationId,
                    repositories,
                    GitHubPermission.minimumDraftDelivery());
        }

        static GitHubConnectionShape userOauth(
                String ownerId,
                CredentialSubject subject,
                String githubUserId,
                Set<String> repositories) {
            return new GitHubConnectionShape(
                    ConnectionKind.OAUTH,
                    ConnectionOwnerType.USER,
                    ownerId,
                    subject,
                    ExternalIdentity.DELEGATED_USER,
                    githubUserId,
                    repositories,
                    GitHubPermission.minimumDraftDelivery());
        }
    }

    private enum GitHubPermission {
        REPOSITORY_METADATA_READ,
        CONTENTS_READ,
        CONTENTS_WRITE,
        PULL_REQUESTS_WRITE,
        ADMINISTRATION,
        ACTIONS,
        SECRETS,
        MEMBERS,
        WEBHOOKS_WRITE;

        static EnumSet<GitHubPermission> minimumDraftDelivery() {
            return EnumSet.of(
                    REPOSITORY_METADATA_READ,
                    CONTENTS_READ,
                    CONTENTS_WRITE,
                    PULL_REQUESTS_WRITE);
        }
    }

    private record RepositoryResource(
            long repositoryId,
            String fullName,
            String defaultBranch,
            boolean fork,
            boolean archived,
            boolean pullAllowed,
            boolean pushAllowed) {

        @Override
        public String toString() {
            return "RepositoryResource[repositoryId=" + repositoryId + ", fullName=" + fullName
                    + ", defaultBranch=" + defaultBranch + "]";
        }
    }

    private record RateLimitSnapshot(
            String resource, int limit, int remaining, int used, Instant resetsAt) {}

    private record CatalogResult(
            List<RepositoryResource> resources, RateLimitSnapshot rateLimit) {

        private CatalogResult {
            resources = List.copyOf(resources);
        }
    }

    private static final class RepositoryCatalogProbe {

        private final URI baseUri;
        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();

        private RepositoryCatalogProbe(URI baseUri) {
            this.baseUri = baseUri;
        }

        CatalogResult discover(GitHubConnectionShape connection, String token) throws Exception {
            String firstPath = connection.kind() == ConnectionKind.GITHUB_APP
                    ? "/installation/repositories?per_page=2&page=1"
                    : "/user/repos?affiliation=owner%2Ccollaborator%2Corganization_member&per_page=2&page=1";
            List<RepositoryResource> resources = new ArrayList<>();
            String next = firstPath;
            RateLimitSnapshot rate = null;
            while (next != null) {
                HttpResponse<String> response = sendGet(next, token);
                if (response.statusCode() != 200) {
                    throw GitHubErrorNormalizer.normalize(
                            response.statusCode(), flatten(response), response.body());
                }
                rate = rateLimit(response);
                JsonNode root = JSON.readTree(response.body());
                JsonNode repositories = root.has("repositories") ? root.get("repositories") : root;
                for (JsonNode item : repositories) {
                    RepositoryResource resource = new RepositoryResource(
                            item.path("id").asLong(),
                            item.path("full_name").asText(),
                            item.path("default_branch").asText(),
                            item.path("fork").asBoolean(),
                            item.path("archived").asBoolean(),
                            item.path("permissions").path("pull").asBoolean(),
                            item.path("permissions").path("push").asBoolean());
                    if (connection.repositoryAllowlist().contains(resource.fullName())
                            && !resource.archived()
                            && !resource.fork()
                            && resource.pullAllowed()
                            && resource.pushAllowed()) {
                        resources.add(resource);
                    }
                }
                next = nextLink(response);
            }
            resources.sort(Comparator.comparing(RepositoryResource::fullName));
            return new CatalogResult(resources, Objects.requireNonNull(rate));
        }

        private HttpResponse<String> sendGet(String path, String token)
                throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                    .timeout(HTTP_TIMEOUT)
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer " + token)
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .GET()
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        private static String nextLink(HttpResponse<?> response) {
            String link = response.headers().firstValue("Link").orElse("");
            for (String part : link.split(",")) {
                if (part.contains("rel=\"next\"")) {
                    int start = part.indexOf('<');
                    int end = part.indexOf('>');
                    if (start >= 0 && end > start) {
                        return URI.create(part.substring(start + 1, end)).getRawPath()
                                + "?" + URI.create(part.substring(start + 1, end)).getRawQuery();
                    }
                }
            }
            return null;
        }

        private static RateLimitSnapshot rateLimit(HttpResponse<?> response) {
            return new RateLimitSnapshot(
                    header(response, "X-RateLimit-Resource"),
                    Integer.parseInt(header(response, "X-RateLimit-Limit")),
                    Integer.parseInt(header(response, "X-RateLimit-Remaining")),
                    Integer.parseInt(header(response, "X-RateLimit-Used")),
                    Instant.ofEpochSecond(Long.parseLong(header(response, "X-RateLimit-Reset"))));
        }

        private static String header(HttpResponse<?> response, String name) {
            return response.headers().firstValue(name).orElseThrow();
        }
    }

    private static final class AskPassCredentialSession implements AutoCloseable {

        private final Path directory;
        private final Path askPassPath;
        private final Path tokenPath;
        private final Map<String, String> environment;
        private byte[] lastResolvedSecret = new byte[0];
        private boolean closed;

        private AskPassCredentialSession(
                Path directory, Path askPassPath, Path tokenPath, Map<String, String> environment) {
            this.directory = directory;
            this.askPassPath = askPassPath;
            this.tokenPath = tokenPath;
            this.environment = Map.copyOf(environment);
        }

        static AskPassCredentialSession open(Path directory, String token) throws IOException {
            Files.createDirectories(directory);
            Path askPass = directory.resolve("github-askpass");
            Path tokenFile = directory.resolve("github-token");
            Files.writeString(tokenFile, token, StandardCharsets.UTF_8);
            Files.writeString(askPass, """
                    #!/bin/sh
                    case "$1" in
                      *Username*) printf '%s\\n' 'x-access-token' ;;
                      *Password*) /bin/cat "$CREWSCOPE_GITHUB_TOKEN_FILE" ;;
                      *) exit 1 ;;
                    esac
                    """, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(directory, Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
                Files.setPosixFilePermissions(tokenFile, Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE));
                Files.setPosixFilePermissions(askPass, Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_EXECUTE));
            } catch (UnsupportedOperationException ignored) {
                assertTrue(askPass.toFile().setExecutable(true, true));
            }
            return new AskPassCredentialSession(
                    directory,
                    askPass,
                    tokenFile,
                    Map.of(
                            "GIT_ASKPASS", askPass.toString(),
                            "GIT_ASKPASS_REQUIRE", "force",
                            "GIT_TERMINAL_PROMPT", "0",
                            "GIT_CONFIG_NOSYSTEM", "1",
                            "GIT_CONFIG_GLOBAL", "/dev/null",
                            "CREWSCOPE_GITHUB_TOKEN_FILE", tokenFile.toString(),
                            "LC_ALL", "C"));
        }

        CredentialUseProof probeGitCredential(List<String> argv, String expectedToken)
                throws Exception {
            ProcessBuilder builder = new ProcessBuilder(argv);
            builder.redirectErrorStream(true);
            builder.environment().clear();
            builder.environment().putAll(environment);
            Process process = builder.start();
            process.getOutputStream().write(
                    "protocol=https\nhost=github.example\n\n".getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Git credential probe timed out");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Git credential probe failed");
            }
            Map<String, String> fields = parseCredentialOutput(output);
            lastResolvedSecret = fields.getOrDefault("password", "")
                    .getBytes(StandardCharsets.UTF_8);
            boolean matched = expectedToken.equals(new String(lastResolvedSecret, StandardCharsets.UTF_8));
            java.util.Arrays.fill(output, (byte) 0);
            return new CredentialUseProof(
                    fields.get("username"), fields.get("host"), matched, lastResolvedSecret);
        }

        private static Map<String, String> parseCredentialOutput(byte[] output) {
            Map<String, String> fields = new HashMap<>();
            String decoded = new String(output, StandardCharsets.UTF_8);
            for (String line : decoded.split("\\R")) {
                int separator = line.indexOf('=');
                if (separator > 0) {
                    fields.put(line.substring(0, separator), line.substring(separator + 1));
                }
            }
            return fields;
        }

        Path askPassPath() {
            return askPassPath;
        }

        Path tokenPath() {
            return tokenPath;
        }

        Map<String, String> environment() {
            return environment;
        }

        String safeAuditSummary() {
            return "AskPassCredentialSession[actionWindow=OPEN, credential=REDACTED]";
        }

        String agentVisibleInput() {
            return "GitHub delivery action uses an opaque credential handle";
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            java.util.Arrays.fill(lastResolvedSecret, (byte) 0);
            Files.deleteIfExists(askPassPath);
            Files.deleteIfExists(tokenPath);
            Files.deleteIfExists(directory);
            closed = true;
        }
    }

    private record CredentialUseProof(
            String username, String host, boolean passwordMatched, byte[] secretBuffer) {

        private CredentialUseProof {
            secretBuffer = Objects.requireNonNull(secretBuffer, "secretBuffer");
        }

        boolean secretBufferCleared() {
            for (byte value : secretBuffer) {
                if (value != 0) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            return "CredentialUseProof[username=" + username + ", host=" + host
                    + ", passwordMatched=" + passwordMatched + ", secret=REDACTED]";
        }
    }

    private record GitFixture(
            Path source, Path remote, Path mirror, String baselineHead, String deliveryHead) {

        static GitFixture create(Path root) throws Exception {
            Files.createDirectories(root);
            Path source = root.resolve("source");
            Path remote = root.resolve("remote.git");
            Path mirror = root.resolve("managed-mirror.git");
            runRequired(root, "git", "init", "--initial-branch=main", source.toString());
            runRequired(source, "git", "config", "user.name", "M5-S04 Fixture");
            runRequired(source, "git", "config", "user.email", "fixture@crewscope.local");
            Files.writeString(source.resolve("README.md"), "baseline\n", StandardCharsets.UTF_8);
            runRequired(source, "git", "add", "README.md");
            runRequired(source, "git", "commit", "-m", "baseline");
            String baseline = runRequired(source, "git", "rev-parse", "HEAD").trim();
            runRequired(root, "git", "clone", "--bare", source.toString(), remote.toString());
            runRequired(root, "git", "clone", "--mirror", remote.toString(), mirror.toString());
            Files.writeString(source.resolve("README.md"), "delivery\n", StandardCharsets.UTF_8);
            runRequired(source, "git", "commit", "-am", "delivery");
            String delivery = runRequired(source, "git", "rev-parse", "HEAD").trim();
            runRequired(root, "git", "--git-dir=" + mirror, "fetch", source.toString(),
                    delivery + ":refs/crewscope/delivery");
            return new GitFixture(source, remote, mirror, baseline, delivery);
        }

        String remoteHead(String branch) throws Exception {
            return runRequired(remote, "git", "rev-parse", "refs/heads/" + branch).trim();
        }
    }

    private record PushOperation(String branch, String headSha, Optional<String> expectedRemoteHead) {

        private PushOperation {
            requireText(branch, "branch");
            requireSha(headSha, "headSha");
            expectedRemoteHead = Objects.requireNonNull(expectedRemoteHead, "expectedRemoteHead");
            expectedRemoteHead.ifPresent(value -> requireSha(value, "expectedRemoteHead"));
        }
    }

    private enum PushOutcome {
        PUSHED,
        ALREADY_PRESENT
    }

    private record PushResult(PushOutcome outcome, String branch, String headSha) {}

    private static final class PushProbe {

        private final Path mirror;
        private final Path remote;
        private final AtomicInteger pushes = new AtomicInteger();

        private PushProbe(Path mirror, Path remote) {
            this.mirror = mirror;
            this.remote = remote;
        }

        PushResult push(PushOperation operation, boolean loseResponse) throws Exception {
            Optional<String> current = findRemoteHead(operation.branch());
            if (current.filter(operation.headSha()::equals).isPresent()) {
                return new PushResult(PushOutcome.ALREADY_PRESENT, operation.branch(), operation.headSha());
            }
            if (!current.equals(operation.expectedRemoteHead())) {
                throw new GitHubDeliveryException(
                        GitHubErrorCode.REMOTE_HEAD_CONFLICT,
                        "Remote branch no longer matches the confirmed action");
            }
            if (current.isPresent() && !isAncestor(current.orElseThrow(), operation.headSha())) {
                throw new GitHubDeliveryException(
                        GitHubErrorCode.NON_FAST_FORWARD,
                        "Delivery head is not a fast-forward of the confirmed remote head");
            }
            List<String> argv = List.of(
                    "git",
                    "--git-dir=" + mirror,
                    "push",
                    "--porcelain",
                    "--force-with-lease=refs/heads/" + operation.branch() + ":"
                            + operation.expectedRemoteHead().orElse(""),
                    remote.toString(),
                    operation.headSha() + ":refs/heads/" + operation.branch());
            runRequired(mirror, argv.toArray(String[]::new));
            pushes.incrementAndGet();
            if (loseResponse) {
                throw new UnknownDeliveryException("Git push result requires remote reconciliation");
            }
            return new PushResult(PushOutcome.PUSHED, operation.branch(), operation.headSha());
        }

        int executedPushes() {
            return pushes.get();
        }

        private Optional<String> findRemoteHead(String branch) throws Exception {
            ProcessResult result = run(remote, "git", "rev-parse", "--verify", "refs/heads/" + branch);
            if (result.exitCode() != 0) {
                return Optional.empty();
            }
            return Optional.of(result.output().trim());
        }

        private boolean isAncestor(String ancestor, String descendant) throws Exception {
            return run(mirror, "git", "merge-base", "--is-ancestor", ancestor, descendant)
                            .exitCode()
                    == 0;
        }
    }

    private record DraftPrOperation(
            String repository,
            String headBranch,
            String baseBranch,
            String headSha,
            String title,
            String body) {

        private DraftPrOperation {
            requireText(repository, "repository");
            requireText(headBranch, "headBranch");
            requireText(baseBranch, "baseBranch");
            requireSha(headSha, "headSha");
            requireText(title, "title");
            requireText(body, "body");
        }
    }

    private enum DraftPrOutcome {
        CREATED,
        ALREADY_PRESENT,
        RECONCILED_AFTER_UNKNOWN
    }

    private record DraftPrResult(DraftPrOutcome outcome, int number, String htmlUrl) {

        @Override
        public String toString() {
            return "DraftPrResult[outcome=" + outcome + ", number=" + number + "]";
        }
    }

    private static final class DraftPullRequestProbe {

        private final URI baseUri;
        private final String token;
        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();

        private DraftPullRequestProbe(URI baseUri, String token) {
            this.baseUri = baseUri;
            this.token = token;
        }

        DraftPrResult ensureDraft(DraftPrOperation operation) throws Exception {
            Optional<DraftPrResult> existing = find(operation);
            if (existing.isPresent()) {
                return existing.orElseThrow();
            }
            String payload = JSON.writeValueAsString(Map.of(
                    "title", operation.title(),
                    "head", operation.headBranch(),
                    "base", operation.baseBranch(),
                    "body", operation.body(),
                    "draft", true,
                    "head_sha", operation.headSha()));
            HttpRequest request = request(repositoryPath(operation.repository()) + "/pulls")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            try {
                HttpResponse<String> response =
                        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 201) {
                    throw GitHubErrorNormalizer.normalize(
                            response.statusCode(), flatten(response), response.body());
                }
                JsonNode body = JSON.readTree(response.body());
                return new DraftPrResult(
                        DraftPrOutcome.CREATED,
                        body.path("number").asInt(),
                        body.path("html_url").asText());
            } catch (IOException unknown) {
                Optional<DraftPrResult> reconciled = find(operation);
                if (reconciled.isPresent()) {
                    DraftPrResult result = reconciled.orElseThrow();
                    return new DraftPrResult(
                            DraftPrOutcome.RECONCILED_AFTER_UNKNOWN,
                            result.number(),
                            result.htmlUrl());
                }
                throw new UnknownDeliveryException(
                        "Draft pull request result requires provider reconciliation");
            }
        }

        private Optional<DraftPrResult> find(DraftPrOperation operation) throws Exception {
            String query = "?state=open&head="
                    + encode("crewscope:" + operation.headBranch())
                    + "&base="
                    + encode(operation.baseBranch());
            HttpResponse<String> response = http.send(
                    request(repositoryPath(operation.repository()) + "/pulls" + query).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw GitHubErrorNormalizer.normalize(
                        response.statusCode(), flatten(response), response.body());
            }
            JsonNode root = JSON.readTree(response.body());
            if (root.isEmpty()) {
                return Optional.empty();
            }
            JsonNode candidate = root.get(0);
            boolean exact = candidate.path("draft").asBoolean()
                    && operation.headBranch().equals(candidate.path("head").path("ref").asText())
                    && operation.headSha().equals(candidate.path("head").path("sha").asText())
                    && operation.baseBranch().equals(candidate.path("base").path("ref").asText())
                    && operation.title().equals(candidate.path("title").asText())
                    && operation.body().equals(candidate.path("body").asText());
            if (!exact) {
                throw new GitHubDeliveryException(
                        GitHubErrorCode.PULL_REQUEST_CONFLICT,
                        "Existing pull request does not match the confirmed action");
            }
            return Optional.of(new DraftPrResult(
                    DraftPrOutcome.ALREADY_PRESENT,
                    candidate.path("number").asInt(),
                    candidate.path("html_url").asText()));
        }

        private HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(baseUri.resolve(path))
                    .timeout(HTTP_TIMEOUT)
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("X-GitHub-Api-Version", "2022-11-28");
        }

        private static String repositoryPath(String repository) {
            return "/repos/" + repository;
        }

        private static String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }

    private enum GitHubErrorCode {
        AUTHENTICATION_REQUIRED,
        PERMISSION_DENIED,
        RATE_LIMITED,
        RESOURCE_UNAVAILABLE,
        CONFLICT,
        VALIDATION_FAILED,
        PROVIDER_UNAVAILABLE,
        REMOTE_HEAD_CONFLICT,
        NON_FAST_FORWARD,
        PULL_REQUEST_CONFLICT
    }

    private static final class GitHubErrorNormalizer {

        private GitHubErrorNormalizer() {}

        static GitHubDeliveryException normalize(
                int status, Map<String, String> headers, String ignoredRawBody) {
            GitHubErrorCode code;
            String summary;
            if (status == 401) {
                code = GitHubErrorCode.AUTHENTICATION_REQUIRED;
                summary = "GitHub authentication failed";
            } else if (status == 403
                    && "0".equalsIgnoreCase(header(headers, "X-RateLimit-Remaining"))) {
                code = GitHubErrorCode.RATE_LIMITED;
                summary = "GitHub request is rate limited";
            } else if (status == 403) {
                code = GitHubErrorCode.PERMISSION_DENIED;
                summary = "GitHub permission is insufficient";
            } else if (status == 404) {
                code = GitHubErrorCode.RESOURCE_UNAVAILABLE;
                summary = "GitHub resource is unavailable";
            } else if (status == 409) {
                code = GitHubErrorCode.CONFLICT;
                summary = "GitHub resource has conflicting state";
            } else if (status == 422) {
                code = GitHubErrorCode.VALIDATION_FAILED;
                summary = "GitHub rejected the request shape";
            } else if (status == 429) {
                code = GitHubErrorCode.RATE_LIMITED;
                summary = "GitHub request is rate limited";
            } else {
                code = GitHubErrorCode.PROVIDER_UNAVAILABLE;
                summary = "GitHub provider is unavailable";
            }
            return new GitHubDeliveryException(code, summary);
        }

        private static String header(Map<String, String> headers, String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse("");
        }
    }

    private static class GitHubDeliveryException extends RuntimeException {

        private final GitHubErrorCode code;

        private GitHubDeliveryException(GitHubErrorCode code, String safeSummary) {
            super(safeSummary);
            this.code = code;
        }

        GitHubErrorCode code() {
            return code;
        }
    }

    private static final class UnknownDeliveryException extends RuntimeException {

        private UnknownDeliveryException(String safeSummary) {
            super(safeSummary);
        }
    }

    private static final class GitHubApiStub implements AutoCloseable {

        private final HttpServer server;
        private final String appToken;
        private final String oauthToken;
        private final List<String> requestPaths = new CopyOnWriteArrayList<>();
        private final AtomicInteger authorizedRequests = new AtomicInteger();
        private final AtomicInteger createdPullRequests = new AtomicInteger();
        private final AtomicBoolean loseNextDraftResponse = new AtomicBoolean();
        private final List<StoredPullRequest> pullRequests = new CopyOnWriteArrayList<>();

        private GitHubApiStub(HttpServer server, String appToken, String oauthToken) {
            this.server = server;
            this.appToken = appToken;
            this.oauthToken = oauthToken;
        }

        static GitHubApiStub start(String appToken, String oauthToken) throws IOException {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            GitHubApiStub stub = new GitHubApiStub(server, appToken, oauthToken);
            server.createContext("/", stub::handle);
            server.start();
            return stub;
        }

        URI baseUri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        List<String> requestPaths() {
            return List.copyOf(requestPaths);
        }

        int authorizedRequests() {
            return authorizedRequests.get();
        }

        int createdPullRequests() {
            return createdPullRequests.get();
        }

        void loseNextDraftResponse() {
            loseNextDraftResponse.set(true);
        }

        String safeSummary() {
            return "GitHubApiStub[requests=" + requestPaths.size() + ", authorization=REDACTED]";
        }

        private void handle(HttpExchange exchange) throws IOException {
            String rawPath = exchange.getRequestURI().getRawPath();
            String rawQuery = exchange.getRequestURI().getRawQuery();
            requestPaths.add(rawPath + (rawQuery == null ? "" : "?" + rawQuery));
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (!("Bearer " + appToken).equals(authorization)
                    && !("Bearer " + oauthToken).equals(authorization)) {
                send(exchange, 401, "{\"message\":\"bad fixture credential\"}");
                return;
            }
            authorizedRequests.incrementAndGet();
            addRateHeaders(exchange);
            if (rawPath.equals("/installation/repositories")) {
                handleCatalog(exchange, true);
            } else if (rawPath.equals("/user/repos")) {
                handleCatalog(exchange, false);
            } else if (rawPath.equals("/repos/crewscope/repository-a/pulls")) {
                handlePullRequests(exchange);
            } else {
                send(exchange, 404, "{\"message\":\"not found\"}");
            }
        }

        private void handleCatalog(HttpExchange exchange, boolean installation) throws IOException {
            int page = query(exchange).getOrDefault("page", "1").equals("2") ? 2 : 1;
            List<Map<String, Object>> repositories;
            if (installation) {
                repositories = page == 1
                        ? List.of(repository(101, "crewscope/repository-a", false, false, true),
                                repository(102, "crewscope/archived", false, true, true))
                        : List.of(repository(103, "crewscope/fork", true, false, true),
                                repository(104, "crewscope/read-only", false, false, false));
            } else {
                repositories = page == 1
                        ? List.of(repository(101, "crewscope/repository-a", false, false, true),
                                repository(201, "zhang/personal-tool", false, false, true))
                        : List.of(repository(202, "other/not-authorized", false, false, true));
            }
            if (page == 1) {
                String endpoint = installation ? "/installation/repositories" : "/user/repos";
                String prefix = installation
                        ? "?per_page=2&page=2"
                        : "?affiliation=owner%2Ccollaborator%2Corganization_member&per_page=2&page=2";
                exchange.getResponseHeaders().set(
                        "Link", "<" + baseUri() + endpoint + prefix + ">; rel=\"next\"");
            }
            Object payload = installation ? Map.of("repositories", repositories) : repositories;
            send(exchange, 200, JSON.writeValueAsString(payload));
        }

        private void handlePullRequests(HttpExchange exchange) throws IOException {
            if (exchange.getRequestMethod().equals("GET")) {
                send(exchange, 200, JSON.writeValueAsString(
                        pullRequests.stream().map(StoredPullRequest::response).toList()));
                return;
            }
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            StoredPullRequest stored = new StoredPullRequest(
                    createdPullRequests.incrementAndGet(),
                    request.path("head").asText(),
                    request.path("base").asText(),
                    request.path("head_sha").asText(),
                    request.path("title").asText(),
                    request.path("body").asText(),
                    request.path("draft").asBoolean());
            pullRequests.add(stored);
            if (loseNextDraftResponse.compareAndSet(true, false)) {
                exchange.close();
                return;
            }
            send(exchange, 201, JSON.writeValueAsString(stored.response()));
        }

        private static Map<String, Object> repository(
                long id, String name, boolean fork, boolean archived, boolean push) {
            return Map.of(
                    "id", id,
                    "full_name", name,
                    "default_branch", "main",
                    "fork", fork,
                    "archived", archived,
                    "permissions", Map.of("pull", true, "push", push));
        }

        private static Map<String, String> query(HttpExchange exchange) {
            Map<String, String> result = new HashMap<>();
            String raw = exchange.getRequestURI().getRawQuery();
            if (raw == null) {
                return result;
            }
            for (String pair : raw.split("&")) {
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    result.put(pair.substring(0, equals), pair.substring(equals + 1));
                }
            }
            return result;
        }

        private static void addRateHeaders(HttpExchange exchange) {
            exchange.getResponseHeaders().set("X-RateLimit-Resource", "core");
            exchange.getResponseHeaders().set("X-RateLimit-Limit", "5000");
            exchange.getResponseHeaders().set("X-RateLimit-Remaining", "4993");
            exchange.getResponseHeaders().set("X-RateLimit-Used", "7");
            exchange.getResponseHeaders().set("X-RateLimit-Reset", "1800000000");
        }

        private static void send(HttpExchange exchange, int status, String body) throws IOException {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private record StoredPullRequest(
            int number,
            String head,
            String base,
            String headSha,
            String title,
            String body,
            boolean draft) {

        Map<String, Object> response() {
            return Map.of(
                    "number", number,
                    "html_url", "https://github.example/crewscope/repository-a/pull/" + number,
                    "head", Map.of("ref", head, "sha", headSha),
                    "base", Map.of("ref", base),
                    "title", title,
                    "body", body,
                    "draft", draft);
        }
    }

    private static Map<String, String> flatten(HttpResponse<?> response) {
        Map<String, String> headers = new HashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, values.get(0));
            }
        });
        return headers;
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty() || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return normalized;
    }

    private static String requireSha(String value, String name) {
        String sha = requireText(value, name);
        if (!sha.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(name + " must be a full SHA-1");
        }
        return sha;
    }

    private static boolean commandSucceeds(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            return process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                    && process.exitValue() == 0;
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static String runRequired(Path workingDirectory, String... command) throws Exception {
        ProcessResult result = run(workingDirectory, command);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Fixture command failed with exit " + result.exitCode());
        }
        return result.output();
    }

    private static ProcessResult run(Path workingDirectory, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Fixture command timed out");
        }
        return new ProcessResult(process.exitValue(), new String(output, StandardCharsets.UTF_8));
    }

    private record ProcessResult(int exitCode, String output) {}
}
