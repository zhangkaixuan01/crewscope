package io.crewscope.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Non-secret deployment coordinates for the one Bootstrap Operator account. */
@ConfigurationProperties("crewscope.security.operator-bootstrap")
public class BootstrapOperatorProperties {

    private String organizationId = "";
    private String username = "crewscope-monitor";
    private String email = "crewscope-monitor@crewscope.local";
    private String displayName = "CrewScope Operator";

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
}
