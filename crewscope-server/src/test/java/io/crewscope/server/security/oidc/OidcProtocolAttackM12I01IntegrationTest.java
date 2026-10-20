package io.crewscope.server.security.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.server.teambeta.AbstractTeamBetaPostgresRedisContainerIntegrationTest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.filter.annotation.TypeExcludeFilters;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The M12-I01 protocol attack matrix: every row drives a hostile signal through the real
 * three-hop flow (or straight at the embedded provider when the attack never reaches
 * CrewScope) and asserts the same three facts — the attempt is refused with the fixed
 * {@code sso_error=denied} code or a provider-side rejection, no Session survives, and no
 * account side effect lands. Each row uses its own subject, so the shared team-beta world
 * needs no truncation.
 */
@Tag("integration")
@ActiveProfiles("team-beta")
@TypeExcludeFilters(OidcProtocolAttackM12I01IntegrationTest.ExcludeTestClasspathConfigurations.class)
@SpringBootTest(
        classes = io.crewscope.server.CrewScopeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OidcProtocolAttackM12I01IntegrationTest
        extends AbstractTeamBetaPostgresRedisContainerIntegrationTest {

    private static final String ORGANIZATION_ID = "0198a475-0831-7000-8000-000000000001";
    private static final String PROVIDER_KEY = "oidc/company";
    private static final String SESSION_COOKIE = "CREWSCOPE_SESSION";
    private static final String LOGIN_CODE_PATH = "/login/oauth2/code/company";

    @TempDir
    static Path artifactRoot;

    static final Path CODING_ROOT = createDirectory("crewscope-m12i01-attacks");

    private static final String TEST_CREDENTIAL_KEYS =
            "v1=7fxmDni7ah4GVkFpXlFp/WJ9Xvlk4lVlEuMO5YMmYPw=";
    private static final String TEST_ACTIVITY_KEY =
            "qy+UtChs7KTKHsPDCKXhhm41lKIf8/3GaSKmClfeqwM=";
    private static final String TEST_LOGIN_DEFENSE_KEY =
            "gL9T+TUAof/s+bhqjftsy17/a4NSctPXA+/6pIZzA1o=";
    private static final String TEST_TASK_TOKEN_KEY =
            "u1WSa5SkDSf8N10uJ2sJE72U/u+ZDGrjaffROM17X5U=";
    private static final String TEST_INVITATION_KEY =
            "asKlHnh1ld0Le3FJyzEj1VDm2poi7ZMyuzisUyIpg6k=";

    private static final EmbeddedOidcIdp IDP = EmbeddedOidcIdp.start();

    @DynamicPropertySource
    static void oidcDeploymentProperties(DynamicPropertyRegistry registry) {
        registry.add("crewscope.security.mode", () -> "oidc");
        registry.add("crewscope.security.oidc.organization-id", () -> ORGANIZATION_ID);
        registry.add("crewscope.artifact.filesystem.root", () -> artifactRoot.toString());
        registry.add("crewscope.credential.encryption.keys", () -> TEST_CREDENTIAL_KEYS);
        registry.add("crewscope.team-activity-realtime.keys.v1", () -> TEST_ACTIVITY_KEY);
        registry.add("crewscope.security.login-defense.hmac-key", () -> TEST_LOGIN_DEFENSE_KEY);
        registry.add("crewscope.security.task-token.keys.v1", () -> TEST_TASK_TOKEN_KEY);
        registry.add("crewscope.invitation.token.hmac-key", () -> TEST_INVITATION_KEY);
        registry.add("crewscope.coding.repository.managed-root",
                () -> prepared(CODING_ROOT, "repositories"));
        registry.add("crewscope.coding.worktree.root", () -> prepared(CODING_ROOT, "worktrees"));
        registry.add("crewscope.coding.worktree.lock-root",
                () -> prepared(CODING_ROOT, "worktree-locks"));
        registry.add("crewscope.coding.git.command-home", () -> prepared(CODING_ROOT, "git-home"));
        registry.add("crewscope.runtime.redis.url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getFirstMappedPort());
        registry.add("crewscope.security.bootstrap.password",
                () -> "m12i01-attack-bootstrap-8e14c7a9b0d3");
        registry.add("spring.security.oauth2.client.registration.company.client-id",
                IDP::clientId);
        registry.add("spring.security.oauth2.client.registration.company.client-secret",
                IDP::clientSecret);
        registry.add("spring.security.oauth2.client.registration.company"
                        + ".authorization-grant-type", () -> "authorization_code");
        registry.add("spring.security.oauth2.client.registration.company.redirect-uri",
                () -> "{baseUrl}/login/oauth2/code/company");
        registry.add("spring.security.oauth2.client.registration.company.scope", () -> "openid");
        registry.add("spring.security.oauth2.client.provider.company.issuer-uri", IDP::issuer);
    }

    @BeforeAll
    static void verifyIdpIsReachable() {
        assertThat(IDP.issuer()).startsWith("http://127.0.0.1:");
    }

    @AfterAll
    static void stopIdp() {
        IDP.close();
    }

    @LocalServerPort
    private int port;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    private WebTestClient client;
    private WebTestClient idpClient;
    private WebClient idpDirect;

    @BeforeEach
    void bindClients() {
        IDP.reset();
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        idpClient = WebTestClient.bindToServer()
                .baseUrl(IDP.issuer())
                .build();
        // The token endpoint returns its raw body for assertion, which the plain WebClient
        // surfaces through exchangeToMono without the assertion-oriented wrapper.
        idpDirect = WebClient.builder().baseUrl(IDP.issuer()).build();
    }

    // --- the attack matrix -----------------------------------------------------

    @Test
    void idTokenSignedWithTheWrongKeyIsRefused() {
        IDP.tamperSigning(EmbeddedOidcIdp.SigningMode.WRONG_KEY);
        String subject = "attack-subject-wrong-key";
        subjectClaims(subject, "Wrong Key", "wrong.key@example.com");

        CookieJar jar = new CookieJar();
        ThreeHops roundTrip = driveThreeHops(jar);

        assertRefusedWithoutSessionOrAccount(roundTrip.landing(), jar, subject);
    }

    @Test
    void unsignedIdTokenIsRefused() {
        IDP.tamperSigning(EmbeddedOidcIdp.SigningMode.ALG_NONE);
        String subject = "attack-subject-alg-none";
        subjectClaims(subject, "Alg None", "alg.none@example.com");

        CookieJar jar = new CookieJar();
        ThreeHops roundTrip = driveThreeHops(jar);

        assertRefusedWithoutSessionOrAccount(roundTrip.landing(), jar, subject);
    }

    @Test
    void issuerMismatchIsRefused() {
        IDP.tamperIssuer("https://attacker.example.com");
        String subject = "attack-subject-issuer";
        subjectClaims(subject, "Issuer Attack", "issuer.attack@example.com");

        CookieJar jar = new CookieJar();
        ThreeHops roundTrip = driveThreeHops(jar);

        assertRefusedWithoutSessionOrAccount(roundTrip.landing(), jar, subject);
    }

    @Test
    void audienceMismatchIsRefused() {
        IDP.tamperAudience("another-rp");
        String subject = "attack-subject-audience";
        subjectClaims(subject, "Audience Attack", "audience.attack@example.com");

        CookieJar jar = new CookieJar();
        ThreeHops roundTrip = driveThreeHops(jar);

        assertRefusedWithoutSessionOrAccount(roundTrip.landing(), jar, subject);
    }

    @Test
    void nonceMismatchIsRefused() {
        IDP.tamperNonce("attacker-chosen-nonce");
        String subject = "attack-subject-nonce";
        subjectClaims(subject, "Nonce Attack", "nonce.attack@example.com");

        CookieJar jar = new CookieJar();
        ThreeHops roundTrip = driveThreeHops(jar);

        assertRefusedWithoutSessionOrAccount(roundTrip.landing(), jar, subject);
    }

    @Test
    void tamperedStateParameterIsRefused() {
        String subject = "attack-subject-state";
        subjectClaims(subject, "State Attack", "state.attack@example.com");
        CookieJar jar = new CookieJar();

        EntityExchangeResult<byte[]> start = getWithJar("/oauth2/authorization/company", jar)
                .expectStatus().isEqualTo(HttpStatus.FOUND.value())
                .expectBody(byte[].class)
                .returnResult();
        jar.captureCookies(start);
        URI consent = client.get()
                .uri(start.getResponseHeaders().getLocation())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FOUND.value())
                .expectBody(byte[].class)
                .returnResult()
                .getResponseHeaders()
                .getLocation();
        assertThat(consent).isNotNull();

        // The attacker rewrites the state echo: the stored authorization request no longer
        // matches, so the callback must be denied before any token exchange happens.
        URI tamperedCallback =
                URI.create(consent.toString().replaceAll("state=[^&]*", "state=tampered"));
        EntityExchangeResult<byte[]> callback = getWithJar(tamperedCallback, jar)
                .expectBody(byte[].class)
                .returnResult();

        assertThat(callback.getResponseHeaders().getLocation().toString())
                .isEqualTo("/login?sso_error=denied");
        assertThat(sessionView(jar).path("authenticated").asBoolean()).isFalse();
        assertThat(count("""
                SELECT COUNT(*) FROM crewscope.login_identity
                WHERE provider = ? AND subject = ?
                """, PROVIDER_KEY, subject)).isZero();
    }

    @Test
    void replayedAuthorizationCodeIsRefused() {
        String subject = "attack-subject-replay";
        subjectClaims(subject, "Replay Attack", "replay.attack@example.com");
        CookieJar jar = new CookieJar();

        ThreeHops roundTrip = driveThreeHops(jar);
        assertThat(roundTrip.landing().getPath()).isEqualTo("/");

        // Replaying the identical callback URL meets the denial: the authorization request was
        // consumed by the legitimate login, so the second pass resolves to nothing.
        EntityExchangeResult<byte[]> replay = getWithJar(roundTrip.callbackUri(), jar)
                .expectBody(byte[].class)
                .returnResult();
        assertThat(replay.getResponseHeaders().getLocation().toString())
                .isEqualTo("/login?sso_error=denied");
        // The replay neither hijacks nor disturbs the legitimately established Session.
        assertThat(sessionView(jar).path("authenticated").asBoolean()).isTrue();
        assertThat(count("""
                SELECT COUNT(*) FROM crewscope.login_identity
                WHERE provider = ? AND subject = ?
                """, PROVIDER_KEY, subject)).isEqualTo(1);

        // Provider-side proof of one-time codes: an attacker-initiated code redeems once and
        // then answers invalid_grant on the second redemption.
        String verifier = "attack-verifier-0123456789012345678901234567890123456789";
        String code = attackCode(applicationRedirectUri(), s256(verifier));
        assertThat(tokenCall(code, applicationRedirectUri(), verifier)).contains("\"id_token\"");
        assertThat(tokenCall(code, applicationRedirectUri(), verifier))
                .contains("invalid_grant");
    }

    @Test
    void tamperedRedirectUriIsRefusedAtTheProvider() {
        String attackerRedirect = "http://127.0.0.1:" + port + "/attacker/callback";

        EntityExchangeResult<byte[]> refused = idpClient.get()
                .uri(uriBuilder -> uriBuilder.path("/authorize")
                        .queryParam("client_id", IDP.clientId())
                        .queryParam("response_type", "code")
                        .queryParam("redirect_uri", attackerRedirect)
                        .queryParam("state", "s")
                        .queryParam("nonce", "n")
                        .build())
                .exchange()
                .expectBody(byte[].class)
                .returnResult();
        assertThat(refused.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        // No redirect follows the refusal, so no open redirect exists for the attacker.
        assertThat(refused.getResponseHeaders().getLocation()).isNull();
        assertThat(body(refused)).contains("invalid_request");

        // A legitimately issued code cannot be redeemed under a swapped redirect_uri either.
        String verifier = "attack-verifier-0123456789012345678901234567890123456789";
        String code = attackCode(applicationRedirectUri(), s256(verifier));
        assertThat(tokenCall(code, attackerRedirect, verifier)).contains("invalid_grant");
    }

    @Test
    void pkceVerifierMismatchIsRefused() {
        // The product resolver forces PKCE on every start; the authorize URL carries a real
        // S256 challenge, so an intercepted code cannot be redeemed without the verifier.
        CookieJar jar = new CookieJar();
        EntityExchangeResult<byte[]> start = getWithJar("/oauth2/authorization/company", jar)
                .expectStatus().isEqualTo(HttpStatus.FOUND.value())
                .expectBody(byte[].class)
                .returnResult();
        String authorizeQuery = start.getResponseHeaders().getLocation().getRawQuery();
        assertThat(authorizeQuery).contains("code_challenge=");
        assertThat(authorizeQuery).contains("code_challenge_method=S256");

        // The provider enforces S256: an attacker-initiated code with the wrong verifier,
        // and a challenge-free start, both fail the token exchange.
        String attackerSubject = "attack-subject-pkce";
        subjectClaims(attackerSubject, "PKCE Attack", "pkce.attack@example.com");
        String verifier = "attack-verifier-0123456789012345678901234567890123456789";
        String code = attackCode(applicationRedirectUri(), s256(verifier));
        assertThat(tokenCall(code, applicationRedirectUri(), "wrong-verifier-0123456789012345678901234567890123456789"))
                .contains("invalid_grant");

        EntityExchangeResult<byte[]> challengeFree = idpClient.get()
                .uri(uriBuilder -> uriBuilder.path("/authorize")
                        .queryParam("client_id", IDP.clientId())
                        .queryParam("response_type", "code")
                        .queryParam("redirect_uri", applicationRedirectUri())
                        .queryParam("state", "s")
                        .queryParam("nonce", "n")
                        .build())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FOUND.value())
                .expectBody(byte[].class)
                .returnResult();
        String challengeFreeCode = queryParam(
                challengeFree.getResponseHeaders().getLocation(), "code");
        assertThat(tokenCall(challengeFreeCode, applicationRedirectUri(), verifier))
                .contains("invalid_grant");

        // The attacker's token never reached CrewScope, so no account exists for the subject.
        assertThat(count("""
                SELECT COUNT(*) FROM crewscope.login_identity
                WHERE provider = ? AND subject = ?
                """, PROVIDER_KEY, attackerSubject)).isZero();
    }

    // --- driving helpers -------------------------------------------------------

    private record ThreeHops(URI callbackUri, URI landing) { }

    /** Drives the full browser round-trip: start, IdP authorize, code callback. */
    private ThreeHops driveThreeHops(CookieJar jar) {
        EntityExchangeResult<byte[]> start = getWithJar("/oauth2/authorization/company", jar)
                .expectStatus().isEqualTo(HttpStatus.FOUND.value())
                .expectBody(byte[].class)
                .returnResult();
        jar.captureCookies(start);
        assertThat(start.getResponseHeaders().getLocation().getRawQuery())
                .contains("code_challenge=");

        EntityExchangeResult<byte[]> consent = client.get()
                .uri(start.getResponseHeaders().getLocation())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FOUND.value())
                .expectBody(byte[].class)
                .returnResult();
        URI callbackUri = consent.getResponseHeaders().getLocation();
        assertThat(callbackUri).isNotNull();

        EntityExchangeResult<byte[]> callback = getWithJar(callbackUri, jar)
                .expectBody(byte[].class)
                .returnResult();
        jar.captureCookies(callback);
        assertThat(callback.getStatus()).isEqualTo(HttpStatus.FOUND);
        return new ThreeHops(callbackUri, callback.getResponseHeaders().getLocation());
    }

    private void assertRefusedWithoutSessionOrAccount(URI landing, CookieJar jar, String subject) {
        assertThat(landing.toString()).isEqualTo("/login?sso_error=denied");
        // The browser jar carries only an anonymous Session: no authentication was established.
        assertThat(sessionView(jar).path("authenticated").asBoolean()).isFalse();
        assertThat(count("""
                SELECT COUNT(*) FROM crewscope.login_identity
                WHERE provider = ? AND subject = ?
                """, PROVIDER_KEY, subject)).isZero();
    }

    private WebTestClient.ResponseSpec getWithJar(Object uri, CookieJar jar) {
        // A String template merges with the client baseUrl; a URI must stay absolute because
        // WebClient.uri(URI) deliberately ignores the baseUrl.
        WebTestClient.RequestHeadersSpec<?> request = uri instanceof URI absolute
                ? client.get().uri(absolute)
                : client.get().uri(String.valueOf(uri));
        jar.apply(request);
        return request.exchange();
    }

    private JsonNode sessionView(CookieJar jar) {
        EntityExchangeResult<byte[]> body = getWithJar("/api/v1/auth/session", jar)
                .expectStatus().isOk()
                .expectBody(byte[].class)
                .returnResult();
        return readJson(body);
    }

    // --- direct provider helpers ------------------------------------------------

    private String applicationRedirectUri() {
        return "http://127.0.0.1:" + port + LOGIN_CODE_PATH;
    }

    /** Starts an attacker-controlled authorization and returns its one-time code. */
    private String attackCode(String redirectUri, String codeChallenge) {
        EntityExchangeResult<byte[]> consent = idpClient.get()
                .uri(uriBuilder -> uriBuilder.path("/authorize")
                        .queryParam("client_id", IDP.clientId())
                        .queryParam("response_type", "code")
                        .queryParam("redirect_uri", redirectUri)
                        .queryParam("state", "attack-state")
                        .queryParam("nonce", "attack-nonce")
                        .queryParam("code_challenge", codeChallenge)
                        .queryParam("code_challenge_method", "S256")
                        .build())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FOUND.value())
                .expectBody(byte[].class)
                .returnResult();
        return queryParam(consent.getResponseHeaders().getLocation(), "code");
    }

    /** Redeems a code straight at the provider; error bodies are the assertion target. */
    private String tokenCall(String code, String redirectUri, String verifier) {
        return idpDirect.post()
                .uri("/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .headers(headers -> headers.setBasicAuth(IDP.clientId(), IDP.clientSecret()))
                .bodyValue("grant_type=authorization_code"
                        + "&code=" + urlEncode(code)
                        + "&redirect_uri=" + urlEncode(redirectUri)
                        + "&code_verifier=" + urlEncode(verifier))
                .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty(""))
                .block(Duration.ofSeconds(10));
    }

    private static String s256(String verifier) {
        try {
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256")
                            .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.US_ASCII);
    }

    private static String queryParam(URI location, String name) {
        String value = UriComponentsBuilder.fromUri(location)
                .build()
                .getQueryParams()
                .getFirst(name);
        assertThat(value).as(name + " of " + location).isNotNull();
        return value;
    }

    // --- shared plumbing ---------------------------------------------------------

    private JsonNode readJson(EntityExchangeResult<byte[]> result) {
        try {
            return objectMapper.readTree(result.getResponseBody());
        } catch (Exception malformed) {
            throw new AssertionError("malformed JSON response", malformed);
        }
    }

    private static String body(EntityExchangeResult<byte[]> result) {
        return new String(result.getResponseBody(), StandardCharsets.UTF_8);
    }

    private void subjectClaims(String subject, String name, String email) {
        IDP.subjectClaims(Map.of(
                "sub", subject,
                "name", name,
                "preferred_username", name.toLowerCase().replace(' ', '.') + "-p",
                "email", email,
                "email_verified", true));
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private static Path createDirectory(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (Exception failure) {
            throw new IllegalStateException("cannot create the coding sandbox root", failure);
        }
    }

    private static String prepared(Path root, String child) {
        try {
            return Files.createDirectories(root.resolve(child)).toString();
        } catch (Exception failure) {
            throw new IllegalStateException("cannot prepare " + child, failure);
        }
    }

    /** Minimal cookie jar preserving the rotating Session across the three hops. */
    private static final class CookieJar {
        private final Map<String, String> values = new LinkedHashMap<>();

        void apply(WebTestClient.RequestHeadersSpec<?> request) {
            values.forEach(request::cookie);
        }

        void captureCookies(EntityExchangeResult<?> result) {
            result.getResponseCookies().forEach((name, cookies) -> {
                ResponseCookie cookie = cookies.get(cookies.size() - 1);
                if (cookie.getMaxAge().isZero()) {
                    values.remove(name);
                } else {
                    values.put(name, cookie.getValue());
                }
            });
        }
    }

    /** Keeps @Configuration classes hosted inside test sources off the component scan. */
    static final class ExcludeTestClasspathConfigurations extends TypeExcludeFilter {
        private static final Pattern TEST_CLASSPATH_HOST =
                Pattern.compile("^io\\.crewscope\\..*(?:TestServer|Test)(?:\\$.*)?$");

        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
            return TEST_CLASSPATH_HOST.matcher(reader.getClassMetadata().getClassName()).matches();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ExcludeTestClasspathConfigurations;
        }

        @Override
        public int hashCode() {
            return ExcludeTestClasspathConfigurations.class.hashCode();
        }
    }
}
