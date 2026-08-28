package io.crewscope.server.security.login;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-owned secrets and trusted proxy boundary for authentication defense. */
@ConfigurationProperties("crewscope.security.login-defense")
public class LoginDefenseProperties {

    private boolean enabled;
    private String environment = "development";
    private String hmacKeyId = "";
    private String hmacKey = "";
    private List<String> trustedProxies = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getHmacKeyId() {
        return hmacKeyId;
    }

    public void setHmacKeyId(String hmacKeyId) {
        this.hmacKeyId = hmacKeyId;
    }

    public String getHmacKey() {
        return hmacKey;
    }

    public void setHmacKey(String hmacKey) {
        this.hmacKey = hmacKey;
    }

    public List<String> getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies == null
                ? new ArrayList<>()
                : new ArrayList<>(trustedProxies);
    }
}
