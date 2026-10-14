package io.crewscope.server.security.oidc;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * Minimal real OIDC provider for the M12-I01 HTTP suites: discovery, authorization-code
 * issuance, a JWKS endpoint and RS256-signed ID tokens. It binds its own reactor-netty port so
 * the CrewScope application resolves it through genuine OIDC discovery, and it is strict by
 * default — one-time codes, exact redirect-uri matching, client authentication and S256
 * code-verifier checks — because the C3 attack rows need an IdP whose refusals are trustworthy.
 * Attack knobs tamper with what a hostile IdP or a man in the middle would control (signing
 * key, iss/aud/nonce claims, alg=none); everything else follows the specification.
 */
public final class EmbeddedOidcIdp implements AutoCloseable {

    /** Selects how the ID token is produced; only the attack rows leave SIGNED. */
    public enum SigningMode {
        SIGNED,
        WRONG_KEY,
        ALG_NONE
    }

    /** One authorization code with everything the token endpoint must re-verify. */
    private record PendingCode(String nonce, String redirectUri, String codeChallenge) { }

    private static final String CLIENT_ID = "crewscope";
    private static final String CLIENT_SECRET = "embedded-idp-secret";
    private static final long TOKEN_LIFETIME_SECONDS = 600;
    /** CrewScope's framework code endpoint; the registration uses the "company" id. */
    private static final String LOGIN_CODE_PATH = "/login/oauth2/code/company";

    private DisposableServer server;
    private final RSAKey signingKey;
    private final RSAKey wrongSigningKey;
    private final Map<String, PendingCode> pendingCodes = new ConcurrentHashMap<>();
    private final AtomicReference<SigningMode> signingMode =
            new AtomicReference<>(SigningMode.SIGNED);
    private final AtomicReference<String> issuerOverride = new AtomicReference<>();
    private final AtomicReference<String> audienceOverride = new AtomicReference<>();
    private final AtomicReference<String> nonceOverride = new AtomicReference<>();
    private final AtomicReference<Map<String, Object>> claimTemplate =
            new AtomicReference<>(Map.of());

    private EmbeddedOidcIdp(RSAKey signingKey, RSAKey wrongSigningKey) {
        this.signingKey = signingKey;
        this.wrongSigningKey = wrongSigningKey;
    }

    /** Binds the IdP on a random loopback port; discovery is reachable as soon as this returns. */
    public static EmbeddedOidcIdp start() {
        RSAKey key;
        RSAKey wrongKey;
        try {
            key = new RSAKeyGenerator(2048).keyID("embedded-idp-signing").generate();
            wrongKey = new RSAKeyGenerator(2048).keyID("embedded-idp-wrong").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("embedded IdP key generation failed", e);
        }
        EmbeddedOidcIdp idp = new EmbeddedOidcIdp(key, wrongKey);
        RouterFunction<ServerResponse> routes = RouterFunctions.route()
                .GET("/.well-known/openid-configuration", idp::discovery)
                .GET("/authorize", idp::authorize)
                .POST("/token", idp::token)
                .GET("/jwks.json", idp::jwks)
                .build();
        idp.server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle(new ReactorHttpHandlerAdapter(RouterFunctions.toHttpHandler(routes)))
                .bindNow();
        return idp;
    }

    public String issuer() {
        return "http://127.0.0.1:" + server.port();
    }

    public String clientId() {
        return CLIENT_ID;
    }

    public String clientSecret() {
        return CLIENT_SECRET;
    }

    /** Clears every attack knob and pending code; each test starts from a compliant IdP. */
    public void reset() {
        signingMode.set(SigningMode.SIGNED);
        issuerOverride.set(null);
        audienceOverride.set(null);
        nonceOverride.set(null);
        claimTemplate.set(Map.of());
        pendingCodes.clear();
    }

    public void tamperSigning(SigningMode mode) {
        signingMode.set(mode);
    }

    public void tamperIssuer(String claimValue) {
        issuerOverride.set(claimValue);
    }

    public void tamperAudience(String claimValue) {
        audienceOverride.set(claimValue);
    }

    public void tamperNonce(String claimValue) {
        nonceOverride.set(claimValue);
    }

    /** Sets the subject claims the next ID tokens carry (sub, name, email, ...). */
    public void subjectClaims(Map<String, Object> claims) {
        claimTemplate.set(Map.copyOf(claims));
    }

    private Mono<ServerResponse> discovery(ServerRequest request) {
        String issuer = issuer();
        Map<String, Object> document = Map.of(
                "issuer", issuer,
                "authorization_endpoint", issuer + "/authorize",
                "token_endpoint", issuer + "/token",
                "jwks_uri", issuer + "/jwks.json",
                "response_types_supported", List.of("code"),
                "subject_types_supported", List.of("public"),
                "id_token_signing_alg_values_supported", List.of("RS256"),
                "code_challenge_methods_supported", List.of("S256"),
                "token_endpoint_auth_methods_supported", List.of("client_secret_basic"));
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(document);
    }

    private Mono<ServerResponse> jwks(ServerRequest request) {
        return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new JWKSet(signingKey.toPublicJWK()).toJSONObject());
    }

    private Mono<ServerResponse> authorize(ServerRequest request) {
        // The registered code endpoint on a loopback origin is the only accepted redirect:
        // any other value is refused without redirecting, so no open redirect is possible.
        if (!request.queryParam("client_id").equals(Optional.of(CLIENT_ID))
                || !request.queryParam("response_type").equals(Optional.of("code"))
                || !isAcceptedRedirectUri(request.queryParam("redirect_uri").orElse(""))) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request");
        }
        String redirectUri = request.queryParam("redirect_uri").orElseThrow();
        String code = UUID.randomUUID().toString();
        pendingCodes.put(code, new PendingCode(
                request.queryParam("nonce").orElse(""),
                redirectUri,
                request.queryParam("code_challenge").orElse("")));
        URI location = URI.create(redirectUri
                + (redirectUri.contains("?") ? "&" : "?")
                + "code=" + code
                + request.queryParam("state").map(state -> "&state=" + state).orElse(""));
        return ServerResponse.status(HttpStatus.FOUND).location(location).build();
    }

    private Mono<ServerResponse> token(ServerRequest request) {
        return request.formData()
                .flatMap(form -> {
                    // client_secret_basic carries the client identity in the Authorization
                    // header only; a redundant form client_id is checked when present.
                    String formClientId = form.getFirst("client_id");
                    if (!"authorization_code".equals(form.getFirst("grant_type"))
                            || !authenticated(request)
                            || (formClientId != null && !CLIENT_ID.equals(formClientId))) {
                        return tokenError("invalid_client");
                    }
                    String code = form.getFirst("code");
                    PendingCode pending = code == null ? null : pendingCodes.remove(code);
                    // Removal makes the code single-use: a replay lands in the null branch.
                    if (pending == null || !pending.redirectUri().equals(form.getFirst("redirect_uri"))) {
                        return tokenError("invalid_grant");
                    }
                    if (!verifierMatches(form.getFirst("code_verifier"), pending.codeChallenge())) {
                        return tokenError("invalid_grant");
                    }
                    return respondIdToken(pending);
                });
    }

    private Mono<ServerResponse> respondIdToken(PendingCode pending) {
        try {
            String idToken = signIdToken(pending);
            Map<String, Object> body = Map.of(
                    "access_token", UUID.randomUUID().toString(),
                    "id_token", idToken,
                    "token_type", "Bearer",
                    "expires_in", TOKEN_LIFETIME_SECONDS);
            return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
        } catch (JOSEException | java.text.ParseException e) {
            return tokenError("server_error");
        }
    }

    private String signIdToken(PendingCode pending)
            throws JOSEException, java.text.ParseException {
        Instant now = Instant.now();
        Map<String, Object> template = claimTemplate.get();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuerOverride.get() != null ? issuerOverride.get() : issuer())
                .audience(audienceOverride.get() != null
                        ? List.of(audienceOverride.get())
                        : List.of(CLIENT_ID))
                .subject(String.valueOf(template.getOrDefault("sub", "user-" + UUID.randomUUID())))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(TOKEN_LIFETIME_SECONDS)))
                .claim("nonce", nonceOverride.get() != null ? nonceOverride.get() : pending.nonce());
        template.forEach((name, value) -> {
            if (!"sub".equals(name)) {
                claims.claim(name, value);
            }
        });
        return switch (signingMode.get()) {
            case SIGNED -> sign(signingKey, claims.build());
            case WRONG_KEY -> sign(wrongSigningKey, claims.build());
            case ALG_NONE -> new PlainJWT(claims.build()).serialize();
        };
    }

    private static String sign(RSAKey key, JWTClaimsSet claims) throws JOSEException {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static boolean isAcceptedRedirectUri(String redirectUri) {
        if (redirectUri.isBlank()) {
            return false;
        }
        try {
            URI requested = URI.create(redirectUri);
            String host = requested.getHost() == null ? "" : requested.getHost().toLowerCase(Locale.ROOT);
            return (host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]"))
                    && LOGIN_CODE_PATH.equals(requested.getPath());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean authenticated(ServerRequest request) {
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET)
                        .getBytes(StandardCharsets.US_ASCII));
        return expected.equals(request.headers().asHttpHeaders().getFirst("Authorization"));
    }

    private static boolean verifierMatches(String verifier, String codeChallenge) {
        // PKCE is mandatory for this IdP: a start without a challenge or a token call without
        // a matching verifier is refused — exactly what the C3 PKCE attack row exercises.
        if (codeChallenge == null || codeChallenge.isBlank() || verifier == null || verifier.isBlank()) {
            return false;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String computed = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(digest.digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            return computed.equals(codeChallenge);
        } catch (NoSuchAlgorithmException e) {
            return false;
        }
    }

    private static Mono<ServerResponse> error(HttpStatus status, String code) {
        return ServerResponse.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("error", code));
    }

    private static Mono<ServerResponse> tokenError(String code) {
        return error(HttpStatus.BAD_REQUEST, code);
    }

    @Override
    public void close() {
        server.disposeNow();
    }
}
