package io.crewscope.server.config;

import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;
import java.util.Optional;

/**
 * Single source for the Organization that anchors every browser identity flow.
 *
 * <p>Local mode follows {@code crewscope.registration.organization-id} exactly as before. OIDC
 * mode makes {@code crewscope.security.oidc.organization-id} authoritative: it is mandatory, and
 * a non-blank registration key naming a different Organization refuses startup instead of letting
 * the two entry points disagree.
 */
public final class BrowserOrganizationAnchor {

  private final SecurityMode mode;
  private final Optional<OrganizationId> registrationOrganization;
  private final Optional<OrganizationId> oidcOrganization;

  private BrowserOrganizationAnchor(
      SecurityMode mode,
      Optional<OrganizationId> registrationOrganization,
      Optional<OrganizationId> oidcOrganization) {
    this.mode = mode;
    this.registrationOrganization = registrationOrganization;
    this.oidcOrganization = oidcOrganization;
  }

  public static BrowserOrganizationAnchor from(
      SecurityMode mode, String registrationOrganizationId, String oidcOrganizationId) {
    SecurityMode requiredMode = Objects.requireNonNull(mode, "mode");
    Optional<OrganizationId> registration = parseOptional(registrationOrganizationId);
    Optional<OrganizationId> oidc = parseOptional(oidcOrganizationId);
    if (requiredMode == SecurityMode.OIDC) {
      if (oidc.isEmpty()) {
        throw new IllegalStateException(
            "crewscope.security.oidc.organization-id is required in oidc mode");
      }
      if (registration.isPresent() && !registration.equals(oidc)) {
        throw new IllegalStateException(
            "crewscope.registration.organization-id must match crewscope.security.oidc"
                + ".organization-id in oidc mode");
      }
    }
    return new BrowserOrganizationAnchor(requiredMode, registration, oidc);
  }

  public SecurityMode mode() {
    return mode;
  }

  /** The anchor browsers resolve against; empty keeps the per-endpoint 503 behaviour in local mode. */
  public Optional<OrganizationId> browserOrganizationId() {
    return mode == SecurityMode.OIDC ? oidcOrganization : registrationOrganization;
  }

  /** The federated anchor, consumed by the OIDC subject extractor. */
  public Optional<OrganizationId> oidcOrganizationId() {
    return oidcOrganization;
  }

  private static Optional<OrganizationId> parseOptional(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(OrganizationId.from(value.strip()));
  }
}
