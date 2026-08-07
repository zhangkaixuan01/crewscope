package io.crewscope.infrastructure.credential;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.crewscope.application.credential.CredentialStore;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Proves that Spring startup fails closed unless a valid external credential key ring exists. */
class CredentialStoreConfigurationTest {

    private static final String CURRENT_KEY_ID = "credential-key-2026-08";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CredentialStoreConfiguration.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void createsKeyRingAndCredentialStoreFromExternalProperties() {
        String encodedKey = encodedKey((byte) 0x41);

        contextRunner
                .withPropertyValues(
                        "crewscope.credential.encryption.current-key-id=" + CURRENT_KEY_ID,
                        "crewscope.credential.encryption.keys=" + CURRENT_KEY_ID + "="
                                + encodedKey)
                .run(context -> {
                    assertTrue(context.isRunning());
                    assertNotNull(context.getBean(CredentialKeyRing.class));
                    assertNotNull(context.getBean(CredentialStore.class));
                    assertNotNull(context.getBean(DatabaseEnvelopeCredentialStore.class));
                });
    }

    @Test
    void refusesToStartWhenExternalPropertiesAreMissing() {
        contextRunner.run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(hasCause(
                    context.getStartupFailure(), CredentialKeyConfigurationException.class));
        });
    }

    @Test
    void refusesInvalidConfigurationWithoutLeakingEncodedMaterial() {
        String encodedKey = encodedKey((byte) 0x63);

        contextRunner
                .withPropertyValues(
                        "crewscope.credential.encryption.current-key-id=" + CURRENT_KEY_ID,
                        "crewscope.credential.encryption.keys=other-key=" + encodedKey)
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure);
                    assertTrue(hasCause(failure, CredentialKeyConfigurationException.class));
                    assertFalse(stackMessages(failure).contains(encodedKey));
                });
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String stackMessages(Throwable throwable) {
        StringBuilder messages = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            messages.append(current.getMessage()).append('\n');
            current = current.getCause();
        }
        return messages.toString();
    }

    private static String encodedKey(byte value) {
        byte[] key = new byte[32];
        Arrays.fill(key, value);
        return Base64.getEncoder().encodeToString(key);
    }
}
