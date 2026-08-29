package io.crewscope.server.security.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.crewscope.application.identity.AuthenticationFlow;
import io.crewscope.application.identity.LoginDefenseTelemetry;
import io.crewscope.application.identity.LoginDefenseUnavailableException;
import io.crewscope.application.identity.LoginIdentifierResource;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** Pure boundary tests for trusted proxy parsing, HMAC separation and metric cardinality. */
class LoginDefenseBoundaryM7I04Test {

    @Test
    void ignoresSpoofedForwardingFromAnUntrustedPeer() throws Exception {
        ControlledNetworkSourceResolver resolver = new ControlledNetworkSourceResolver(List.of());
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-For", "10.1.2.3");

        assertThat(resolver.resolve(remote("203.0.113.7"), headers).canonicalValue())
                .isEqualTo("ipv4:cb007100/24");
    }

    @Test
    void stripsATrustedProxyChainFromRightToLeft() throws Exception {
        ControlledNetworkSourceResolver resolver =
                new ControlledNetworkSourceResolver(List.of("10.0.0.0/8", "2001:db8::/32"));
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-For", "198.51.100.9, 10.0.0.8");

        assertThat(resolver.resolve(remote("10.0.0.9"), headers).canonicalValue())
                .isEqualTo("ipv4:c6336400/24");

        HttpHeaders ipv6Headers = new HttpHeaders();
        ipv6Headers.add("X-Forwarded-For", "2001:4860:4860::8888");
        assertThat(resolver.resolve(remote("2001:db8::1"), ipv6Headers).canonicalValue())
                .isEqualTo("ipv6:20014860486000000000000000000000/64");
    }

    @Test
    void acceptsOnlyNumericUnresolvedAddressesProducedByForwardedNormalization() {
        ControlledNetworkSourceResolver resolver = new ControlledNetworkSourceResolver(List.of());

        assertThat(resolver.resolve(
                        InetSocketAddress.createUnresolved("192.168.65.1", 18_080),
                        new HttpHeaders())
                .canonicalValue())
                .isEqualTo("ipv4:c0a84100/24");
        assertThatThrownBy(() -> resolver.resolve(
                        InetSocketAddress.createUnresolved("attacker.example", 18_080),
                        new HttpHeaders()))
                .isExactlyInstanceOf(LoginDefenseUnavailableException.class);
    }

    @Test
    void failsClosedOnMalformedOrUnboundedTrustedForwarding() throws Exception {
        ControlledNetworkSourceResolver resolver =
                new ControlledNetworkSourceResolver(List.of("10.0.0.0/8"));
        HttpHeaders malformed = new HttpHeaders();
        malformed.add("X-Forwarded-For", "attacker.example");

        assertThatThrownBy(() -> resolver.resolve(remote("10.0.0.9"), malformed))
                .isExactlyInstanceOf(LoginDefenseUnavailableException.class)
                .hasMessage("Authentication defense is temporarily unavailable")
                .hasNoCause();
    }

    @Test
    void separatesHmacPurposesAndNeverEmbedsPreimages() {
        String secret = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        LoginDefenseResourceHasher hasher = new LoginDefenseResourceHasher("v1", secret);

        String identifier = hasher.digest("login:identifier", "alice@example.com");
        String network = hasher.digest("login:network", "alice@example.com");

        assertThat(identifier)
                .startsWith("v1:")
                .isNotEqualTo(network)
                .doesNotContain("alice", "example.com");
        assertThat(LoginIdentifierResource.fromSubmitted("Ａlice").canonicalValue())
                .isEqualTo("alice");
        assertThat(LoginIdentifierResource.fromSubmitted("x".repeat(1_025)).toString())
                .isEqualTo("LoginIdentifierResource[REDACTED]");
    }

    @Test
    void emitsOnlyTheThreeFixedEnumTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LoginDefenseMetrics metrics = new LoginDefenseMetrics(registry);

        metrics.record(
                AuthenticationFlow.LOGIN,
                LoginDefenseTelemetry.Operation.RESOURCE_ADMISSION,
                LoginDefenseTelemetry.Outcome.IDENTIFIER_LIMITED);

        List<Meter> meters = registry.getMeters();
        assertThat(meters).hasSize(1);
        assertThat(meters.get(0).getId().getTags())
                .extracting(tag -> tag.getKey() + "=" + tag.getValue())
                .containsExactly(
                        "flow=login",
                        "operation=resource_admission",
                        "outcome=identifier_limited");
    }

    private static InetSocketAddress remote(String address) throws Exception {
        return new InetSocketAddress(InetAddress.getByAddress(parse(address)), 443);
    }

    private static byte[] parse(String address) throws Exception {
        // Test fixtures are constants; production header parsing never performs DNS resolution.
        return InetAddress.getByName(address).getAddress();
    }
}
