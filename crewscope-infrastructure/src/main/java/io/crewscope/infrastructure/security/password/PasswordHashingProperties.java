package io.crewscope.infrastructure.security.password;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-profile password Hash concurrency selected by ADR-025. */
@ConfigurationProperties("crewscope.security.password")
public class PasswordHashingProperties {

    private int hashPermits = 2;

    public int getHashPermits() {
        return hashPermits;
    }

    public void setHashPermits(int hashPermits) {
        this.hashPermits = hashPermits;
    }
}
