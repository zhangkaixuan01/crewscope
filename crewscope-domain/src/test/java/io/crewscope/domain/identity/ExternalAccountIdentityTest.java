package io.crewscope.domain.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** ADR-036 frozen derivation table turning one trusted external subject into account fields. */
class ExternalAccountIdentityTest {

    private static final ExternalAccountIdentity FULLY_CLAIMED = new ExternalAccountIdentity(
            "company",
            "subject-001",
            Optional.of("Alice Chen"),
            Optional.of("alice.chen"),
            Optional.of("alice@example.com"),
            true);

    @Test
    void providerKeyIsTheRegistrationPrefixAndTheSubjectFormsTheIdentityKey() {
        assertEquals("oidc/company", FULLY_CLAIMED.provider().value());
        assertEquals("subject-001", FULLY_CLAIMED.identityKey().subject().value());
        assertEquals("oidc/company", FULLY_CLAIMED.identityKey().provider().value());
    }

    @Test
    void displayNameFollowsTheFirstNonEmptyClaimWithTheSubjectAsBackstop() {
        assertEquals("Alice Chen", FULLY_CLAIMED.displayName());
        assertEquals(
                "alice.chen",
                claimed(Optional.empty(), Optional.of("alice.chen"), Optional.empty())
                        .displayName());
        assertEquals(
                "alice@example.com",
                claimed(Optional.empty(), Optional.empty(), Optional.of("alice@example.com"))
                        .displayName());
        assertEquals("subject-001", FULLY_CLAIMED.subject());
    }

    @Test
    void onlyAVerifiedEmailIsAdoptedAndAnythingElseSynthesizesTheReservedDomain() {
        assertEquals("alice@example.com", FULLY_CLAIMED.email(FULLY_CLAIMED.username(0)));
        assertEquals(
                "alice@example.com",
                new ExternalAccountIdentity(
                        "company", "subject-001",
                        Optional.empty(), Optional.empty(),
                        Optional.of("alice@example.com"), true)
                        .email(new Username("alice.chen")));
        assertEquals(
                "alice.chen@sso.oidc.invalid",
                new ExternalAccountIdentity(
                        "company", "subject-001",
                        Optional.empty(), Optional.empty(),
                        Optional.of("alice@example.com"), false)
                        .email(new Username("alice.chen")));
    }

    @Test
    void attemptZeroPrefersAUsablePreferredUsernameAndDegradesOtherwise() {
        assertEquals("alice.chen", FULLY_CLAIMED.username(0).displayValue());
        // Leading separator is not a usable local username; the digest derivation takes over.
        assertEquals(
                derivedUsername("company", "subject-001", 0),
                claimed(Optional.empty(), Optional.of("-lead"), Optional.empty())
                        .username(0)
                        .displayValue());
        assertEquals(
                derivedUsername("company", "subject-001", 0),
                claimed(Optional.empty(), Optional.empty(), Optional.empty())
                        .username(0)
                        .displayValue());
    }

    @Test
    void furtherAttemptsReDeriveDeterministicallyAndStayMutuallyDistinct() {
        String zero = derivedUsername("company", "subject-001", 0);
        String one = derivedUsername("company", "subject-001", 1);
        String three = derivedUsername("company", "subject-001", 3);
        assertEquals(zero, claimedWithSubjectOnly("subject-001").username(0).displayValue());
        assertEquals(one, claimedWithSubjectOnly("subject-001").username(1).displayValue());
        assertEquals(three, claimedWithSubjectOnly("subject-001").username(3).displayValue());
        assertEquals(16, one.length());
        assertTrue(one.startsWith("sso-"));
        assertNotEquals(zero, one);
        assertNotEquals(one, three);
        // Same claims under a different registration derive different accounts.
        assertNotEquals(one, derivedUsername("other", "subject-001", 1));
        assertNotEquals(one, derivedUsername("company", "subject-002", 1));
    }

    @Test
    void derivationAttemptsAreBounded() {
        assertEquals(3, ExternalAccountIdentity.maximumDerivationAttempts());
        assertThrows(
                IllegalArgumentException.class,
                () -> FULLY_CLAIMED.username(-1));
        assertThrows(
                IllegalArgumentException.class,
                () -> FULLY_CLAIMED.username(4));
    }

    @Test
    void compactValidationRejectsMalformedCoordinates() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExternalAccountIdentity("", "subject", Optional.empty(),
                        Optional.empty(), Optional.empty(), false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExternalAccountIdentity("x".repeat(65), "subject", Optional.empty(),
                        Optional.empty(), Optional.empty(), false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExternalAccountIdentity("company", " ", Optional.empty(),
                        Optional.empty(), Optional.empty(), false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExternalAccountIdentity("company", "s".repeat(256), Optional.empty(),
                        Optional.empty(), Optional.empty(), false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExternalAccountIdentity("company", "subject",
                        Optional.of("n".repeat(257)), Optional.empty(), Optional.empty(), false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExternalAccountIdentity("company", "subject", Optional.empty(),
                        Optional.of("preferred\u0007"), Optional.empty(), false));
        assertThrows(
                NullPointerException.class,
                () -> new ExternalAccountIdentity("company", "subject", null,
                        Optional.empty(), Optional.empty(), false));
    }

    private static ExternalAccountIdentity claimedWithSubjectOnly(String subject) {
        return new ExternalAccountIdentity(
                "company", subject, Optional.empty(), Optional.empty(), Optional.empty(), false);
    }

    private static ExternalAccountIdentity claimed(
            Optional<String> name, Optional<String> preferred, Optional<String> email) {
        return new ExternalAccountIdentity(
                "company", "subject-001", name, preferred, email, false);
    }

    /** Independent re-derivation of the frozen digest vector, not a call into the unit. */
    private static String derivedUsername(String registrationId, String subject, int attempt) {
        String preimage = "oidc/" + registrationId + "|" + subject + "|" + attempt;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(preimage.getBytes(StandardCharsets.UTF_8));
            return "sso-" + HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
