package io.crewscope.infrastructure.credential;

import io.crewscope.application.credential.CredentialStore;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Fail-closed Spring wiring for externally supplied credential encryption keys. */
@Configuration(proxyBeanMethods = false)
public class CredentialStoreConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(CredentialKeyRing.class)
    CredentialKeyRing credentialKeyRing(
            @Value("${crewscope.credential.encryption.current-key-id:}") String currentKeyId,
            @Value("${crewscope.credential.encryption.keys:}") String encodedKeys) {
        return new CredentialKeyRingParser().parse(currentKeyId, encodedKeys);
    }

    @Bean
    @ConditionalOnMissingBean(CredentialStore.class)
    DatabaseEnvelopeCredentialStore databaseEnvelopeCredentialStore(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            CredentialKeyRing keyRing) {
        return new DatabaseEnvelopeCredentialStore(
                jdbcTemplate, objectMapper, keyRing, new SecureRandom(), Clock.systemUTC());
    }
}
