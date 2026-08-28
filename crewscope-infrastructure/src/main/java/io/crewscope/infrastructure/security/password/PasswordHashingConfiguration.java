package io.crewscope.infrastructure.security.password;

import io.crewscope.application.identity.LocalCredentialStore;
import io.crewscope.application.identity.LocalPasswordAuthentication;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Production assembly for the ADR-025 Argon2id writer and bounded compatibility reader. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PasswordHashingProperties.class)
public class PasswordHashingConfiguration {

    static final Duration ADMISSION_WAIT = Duration.ofMillis(100);

    @Bean("localCredentialPasswordEncoder")
    PasswordEncoder localCredentialPasswordEncoder() {
        PasswordEncoder current = new Argon2PasswordEncoder(16, 32, 1, 32_768, 3);
        Map<String, PasswordEncoder> readers = new LinkedHashMap<>();
        readers.put("argon2id", current);
        readers.put("bcrypt", new BCryptPasswordEncoder());
        return new DelegatingPasswordEncoder("argon2id", readers);
    }

    @Bean(destroyMethod = "close")
    PasswordHashAdmissionExecutor passwordHashAdmissionExecutor(
            PasswordHashingProperties properties) {
        int permits = properties.getHashPermits();
        int processors = Runtime.getRuntime().availableProcessors();
        if (permits < 1 || permits > 8 || permits > processors) {
            throw new IllegalStateException(
                    "Password Hash permits must be positive and no greater than CPU count or 8");
        }
        return new PasswordHashAdmissionExecutor(permits, ADMISSION_WAIT);
    }

    @Bean
    LocalPasswordAuthentication localPasswordAuthentication(
            @Qualifier("localCredentialPasswordEncoder") PasswordEncoder encoder,
            PasswordHashAdmissionExecutor admission,
            LocalCredentialStore credentials) {
        return new LocalPasswordAuthenticationAdapter(
                encoder, admission, credentials, Clock.systemUTC());
    }
}
