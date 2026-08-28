package io.crewscope.infrastructure.security.invitation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.team.InvitationToken;
import io.crewscope.application.team.InvitationTokenDigester;
import io.crewscope.application.team.InvitationTokenGenerator;
import io.crewscope.domain.team.InvitationTokenDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Cryptographic shape and deterministic lookup tests for M7-I06 invitation tokens. */
class InvitationTokenSecurityM7I06Test {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(InvitationTokenSecurityConfiguration.class);

    @Test
    void generatorProducesIndependentCanonical256BitSecretsWithoutStringLeakage() {
        SecureInvitationTokenGenerator generator = new SecureInvitationTokenGenerator();

        InvitationToken first = generator.generate();
        InvitationToken second = generator.generate();

        assertEquals(43, first.reveal().length());
        assertEquals(32, Base64.getUrlDecoder().decode(first.reveal()).length);
        assertFalse(first.reveal().equals(second.reveal()));
        assertEquals("[REDACTED]", first.toString());
    }

    @Test
    void hmacIsPurposeStableKeyedAndRejectsWeakConfiguration() {
        InvitationToken token = new SecureInvitationTokenGenerator(
                        new FixedSecureRandom((byte) 7))
                .generate();
        String firstKey = key((byte) 1);
        String secondKey = key((byte) 2);

        InvitationTokenDigest first =
                new HmacSha256InvitationTokenDigester(firstKey).digest(token);
        InvitationTokenDigest replay =
                new HmacSha256InvitationTokenDigester(firstKey).digest(token);
        InvitationTokenDigest different =
                new HmacSha256InvitationTokenDigester(secondKey).digest(token);

        assertTrue(first.matches(replay));
        assertFalse(first.matches(different));
        assertEquals(64, first.valueForPersistence().length());
        assertFalse(first.valueForPersistence().contains(token.reveal()));
        assertThrows(
                IllegalStateException.class,
                () -> new HmacSha256InvitationTokenDigester(
                        Base64.getEncoder().encodeToString(new byte[31])));
        assertThrows(
                IllegalStateException.class,
                () -> new HmacSha256InvitationTokenDigester("not-base64"));
    }

    @Test
    void productionAssemblyIsDisabledByDefaultAndFailsClosedWithoutAKey() {
        context.run(result -> {
            assertTrue(result.isRunning());
            assertEquals(0, result.getBeansOfType(InvitationTokenGenerator.class).size());
            assertEquals(0, result.getBeansOfType(InvitationTokenDigester.class).size());
        });
        context.withPropertyValues("crewscope.invitation.token.enabled=true")
                .run(result -> {
                    Throwable failure = result.getStartupFailure();
                    assertNotNull(failure);
                    assertTrue(rootCause(failure).getMessage()
                            .contains("Invitation token HMAC key"));
                });
        context.withPropertyValues(
                        "crewscope.invitation.token.enabled=true",
                        "crewscope.invitation.token.hmac-key=" + key((byte) 8))
                .run(result -> {
                    assertTrue(result.isRunning());
                    assertEquals(1, result.getBeansOfType(InvitationTokenGenerator.class).size());
                    assertEquals(1, result.getBeansOfType(InvitationTokenDigester.class).size());
                });
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String key(byte seed) {
        byte[] value = new byte[32];
        value[0] = seed;
        return Base64.getEncoder().encodeToString(value);
    }

    private static final class FixedSecureRandom extends SecureRandom {
        private final byte value;

        private FixedSecureRandom(byte value) {
            this.value = value;
        }

        @Override
        public void nextBytes(byte[] bytes) {
            java.util.Arrays.fill(bytes, value);
        }
    }
}
