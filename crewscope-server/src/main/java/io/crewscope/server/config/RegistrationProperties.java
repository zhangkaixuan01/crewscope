package io.crewscope.server.config;

import io.crewscope.domain.identity.RegistrationMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-owned local account registration mode exposed later by the Session projection. */
@ConfigurationProperties("crewscope.registration")
public class RegistrationProperties {

  private RegistrationMode mode = RegistrationMode.OPEN;
  private String organizationId = "";

  public RegistrationMode getMode() {
    return mode;
  }

  public void setMode(RegistrationMode mode) {
    this.mode = mode;
  }

  public String getOrganizationId() {
    return organizationId;
  }

  public void setOrganizationId(String organizationId) {
    this.organizationId = organizationId;
  }
}
