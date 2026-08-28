package io.crewscope.server.security.session;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded browser Session policy shared by registration and login. */
@ConfigurationProperties("crewscope.security.session")
public class BrowserSessionProperties {

    private Duration ttl = Duration.ofHours(12);
    private int maximumSessions = 5;
    private String namespace = "crewscope:session";

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public int getMaximumSessions() {
        return maximumSessions;
    }

    public void setMaximumSessions(int maximumSessions) {
        this.maximumSessions = maximumSessions;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }
}
