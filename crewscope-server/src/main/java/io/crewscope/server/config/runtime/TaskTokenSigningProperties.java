package io.crewscope.server.config.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** External HMAC key ring for Task Token signing and verification. */
@ConfigurationProperties(prefix = "crewscope.security.task-token")
public class TaskTokenSigningProperties {

    private String issuer = "crewscope";
    private String currentKeyId = "";
    private Map<String, String> keys = new LinkedHashMap<>();

    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public String getCurrentKeyId() { return currentKeyId; }
    public void setCurrentKeyId(String currentKeyId) { this.currentKeyId = currentKeyId; }
    public Map<String, String> getKeys() { return keys; }
    public void setKeys(Map<String, String> keys) { this.keys = keys; }
}
