package io.crewscope.server.security.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;

/**
 * Pins the embedded IdP's own contract: the M12-I01 HTTP suites trust its refusals, so its
 * happy path (discovery, PKCE-bound code exchange, RS256 ID token) and its failure modes
 * (redirect tampering, code replay, wrong verifier, missing client secret, attack knobs) are
 * verified here once instead of re-derived by every dependent test.
 */
class EmbeddedOidcIdpTest {

    private static final String REDIRECT_URI = "http://127.0.0.1:1/login/oauth2/code/company";
    private static final Pattern CODE_PATTERN = Pattern.compile("[0-9a-f-]{36}");

    private static EmbeddedOidcIdp idp;
    private static WebClient client;

    @BeforeAll
    static void startIdp() {
        idp = EmbeddedOidcIdp.start();
        client = WebClient.builder().baseUrl(idp.issuer()).build();
    }

    @AfterAll
    static void stopIdp() {
        idp.close();
    }

    @BeforeEach
    void resetKnobs() {
        idp.reset();
    }

    @Test
    void discoveryAdvertisesTheConfiguredEndpoints() throws Exception {
        com.fasterxml.jackson.databind.JsonNode document =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(getBody("/.well-known/openid-configuration"));
        assertThat(document.path("issuer").asText()).isEqualTo(idp.issuer());
        assertThat(document.path("authorization_endpoint").asText())
                .isEqualTo(idp.issuer() + "/authorize");
        assertThat(document.path("token_endpoint").asText())
                .isEqualTo(idp.issuer() + "/token");
        assertThat(document.path("jwks_uri").asText()).isEqualTo(idp.issuer() + "/jwks.json");
    }

    @Test
    void jwksExposesOneRsaSigningKey() throws Exception {
        JWKSet keys = JWKSet.parse(getBody("/jwks.json"));
        assertThat(keys.getKeys()).hasSize(1);
        assertThat(keys.getKeys().get(0)).isInstanceOf(RSAKey.class);
        assertThat(keys.getKeys().get(0).getKeyID()).isEqualTo("embedded-idp-signing");
    }

    @Test
    void authorizeRedirectsBackWithStateAndOpaqueCode() {
        java.net.URI location = authorize("state-123", pkceChallenge(), "nonce-1");
        assertThat(location.getRawQuery()).contains("code=").contains("state=state-123");
        String code = queryParameter(location, "code");
        assertThat(CODE_PATTERN.matcher(code).matches()).as("opaque code").isTrue();
    }

    @Test
    void authorizeRefusesRedirectUrisItWasNotRegisteredFor() {
        org.springframework.http.HttpStatusCode status = client.get()
                .uri(builder -> builder.path("/authorize")
                        .queryParam("client_id", idp.clientId())
                        .queryParam("response_type", "code")
                        .queryParam("redirect_uri", "https://evil.example/callback")
                        .queryParam("state", "s")
                        .build())
                .exchangeToMono(response -> reactor.core.publisher.Mono.just(response.statusCode()))
                .block();
        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void tokenExchangeReturnsSignedIdTokenWithTheRequestedClaims() throws Exception {
        idp.subjectClaims(Map.of(
                "sub", "subject-1",
                "name", "Ada Lovelace",
                "preferred_username", "ada",
                "email", "ada@example.com",
                "email_verified", true));
        Pkce pkce = Pkce.generate();
        java.net.URI location = authorize("state-1", pkce.challenge(), "nonce-2");
        String idToken = exchange(queryParameter(location, "code"), pkce.verifier());
        SignedJWT jwt = SignedJWT.parse(idToken);
        assertThat(jwt.verify(new RSASSAVerifier(publicKey()))).isTrue();
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo(idp.issuer());
        assertThat(claims.getAudience()).containsExactly(idp.clientId());
        assertThat(claims.getSubject()).isEqualTo("subject-1");
        assertThat(claims.getStringClaim("nonce")).isEqualTo("nonce-2");
        assertThat(claims.getStringClaim("name")).isEqualTo("Ada Lovelace");
    }

    @Test
    void codesAreSingleUse() {
        Pkce pkce = Pkce.generate();
        String code = queryParameter(authorize("s", pkce.challenge(), "n"), "code");
        exchange(code, pkce.verifier());
        assertThat(tokenCall(code, pkce.verifier()).contains("invalid_grant")).isTrue();
    }

    @Test
    void wrongPkceVerifierIsRefused() {
        Pkce pkce = Pkce.generate();
        String code = queryParameter(authorize("s", pkce.challenge(), "n"), "code");
        assertThat(tokenCall(code, "wrong-verifier").contains("invalid_grant")).isTrue();
    }

    @Test
    void missingClientAuthenticationIsRefused() {
        Pkce pkce = Pkce.generate();
        String code = queryParameter(authorize("s", pkce.challenge(), "n"), "code");
        assertThat(tokenCall(code, pkce.verifier(), false).contains("invalid_client")).isTrue();
    }

    @Test
    void wrongKeyKnobProducesATokenTheAdvertisedJwksCannotVerify() throws Exception {
        idp.tamperSigning(EmbeddedOidcIdp.SigningMode.WRONG_KEY);
        Pkce pkce = Pkce.generate();
        String code = queryParameter(authorize("s", pkce.challenge(), "n"), "code");
        SignedJWT jwt = SignedJWT.parse(exchange(code, pkce.verifier()));
        assertThat(jwt.verify(new RSASSAVerifier(publicKey()))).isFalse();
    }

    @Test
    void algNoneKnobProducesAnUnsignedToken() throws Exception {
        idp.tamperSigning(EmbeddedOidcIdp.SigningMode.ALG_NONE);
        Pkce pkce = Pkce.generate();
        String code = queryParameter(authorize("s", pkce.challenge(), "n"), "code");
        PlainJWT jwt = (PlainJWT) com.nimbusds.jwt.JWTParser.parse(exchange(code, pkce.verifier()));
        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("none");
    }

    @Test
    void claimOverrideKnobsReachTheIssuedToken() throws Exception {
        idp.tamperIssuer("https://attacker.example");
        idp.tamperAudience("other-client");
        idp.tamperNonce("replaced-nonce");
        Pkce pkce = Pkce.generate();
        String code = queryParameter(authorize("s", pkce.challenge(), "genuine-nonce"), "code");
        JWTClaimsSet claims = SignedJWT.parse(exchange(code, pkce.verifier())).getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("https://attacker.example");
        assertThat(claims.getAudience()).containsExactly("other-client");
        assertThat(claims.getStringClaim("nonce")).isEqualTo("replaced-nonce");
    }

    // --- helpers -------------------------------------------------------------

    private static RSAKey publicKey() throws Exception {
        return (RSAKey) JWKSet.parse(getBody("/jwks.json")).getKeys().get(0);
    }

    private static String getBody(String path) {
        return client.get().uri(path).retrieve().bodyToMono(String.class).block();
    }

    private static java.net.URI authorize(String state, String challenge, String nonce) {
        java.net.URI location = client.get()
                .uri(builder -> authorizeUri(builder, state, challenge, nonce))
                .retrieve()
                .toBodilessEntity()
                .block()
                .getHeaders()
                .getLocation();
        assertThat(location).isNotNull();
        return location;
    }

    private static URI authorizeUri(
            UriBuilder builder, String state, String challenge, String nonce) {
        return builder.path("/authorize")
                .queryParam("client_id", idp.clientId())
                .queryParam("response_type", "code")
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("state", state)
                .queryParam("nonce", nonce)
                .queryParam("scope", "openid")
                .queryParam("code_challenge", challenge)
                .queryParam("code_challenge_method", "S256")
                .build();
    }

    private static String exchange(String code, String verifier) {
        String body = tokenCall(code, verifier);
        assertThat(body).contains("id_token");
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(body)
                    .path("id_token")
                    .asText();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("malformed token response: " + body, e);
        }
    }

    private static String tokenCall(String code, String verifier) {
        return tokenCall(code, verifier, true);
    }

    /** The 4xx bodies are the assertion target, so the client surfaces them instead of throwing. */
    private static String tokenCall(String code, String verifier, boolean authenticated) {
        org.springframework.web.reactive.function.client.WebClient.RequestBodySpec request =
                client.post()
                        .uri("/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (authenticated) {
            request.headers(
                    headers -> headers.setBasicAuth(idp.clientId(), idp.clientSecret()));
        }
        return request
                .bodyValue("grant_type=authorization_code&code=" + code
                        + "&redirect_uri=" + REDIRECT_URI
                        + "&client_id=" + idp.clientId()
                        + "&code_verifier=" + verifier)
                .exchangeToMono(response -> response.bodyToMono(String.class)
                        .defaultIfEmpty(""))
                .block();
    }

    private static String queryParameter(java.net.URI uri, String name) {
        String query = uri.getRawQuery();
        for (String pair : query.split("&")) {
            String[] keyAndValue = pair.split("=", 2);
            if (name.equals(keyAndValue[0])) {
                return keyAndValue[1];
            }
        }
        throw new AssertionError("missing query parameter " + name + " in " + query);
    }

    private static String pkceChallenge() {
        return Pkce.generate().challenge();
    }

    private record Pkce(String verifier, String challenge) {
        static Pkce generate() {
            String verifier = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(("verifier-" + System.nanoTime())
                            .getBytes(StandardCharsets.US_ASCII));
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                String challenge = Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(digest.digest(verifier.getBytes(StandardCharsets.US_ASCII)));
                return new Pkce(verifier, challenge);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
