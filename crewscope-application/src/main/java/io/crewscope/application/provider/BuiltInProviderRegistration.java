package io.crewscope.application.provider;

import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderDefinitionId;
import io.crewscope.domain.provider.ProviderImplementationId;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/** Immutable product-owned Provider contract used by bootstrap, queries and runtime adapters. */
public record BuiltInProviderRegistration(
    String definitionKey,
    ProviderType type,
    String interfaceVersion,
    String displayName,
    String implementationKey,
    String implementationVersion,
    ProviderCapabilities capabilities) {

  public BuiltInProviderRegistration {
    definitionKey = requireText(definitionKey, "definitionKey");
    type = Objects.requireNonNull(type, "type");
    interfaceVersion = requireText(interfaceVersion, "interfaceVersion");
    displayName = requireText(displayName, "displayName");
    implementationKey = requireText(implementationKey, "implementationKey");
    implementationVersion = requireText(implementationVersion, "implementationVersion");
    capabilities = Objects.requireNonNull(capabilities, "capabilities");
  }

  public ProviderDefinitionId definitionId(OrganizationId organizationId) {
    return new ProviderDefinitionId(
        stableUuid(
            "crewscope:built-in-provider:definition:v1:",
            definitionKey + ":" + organizationId.value()));
  }

  public ProviderImplementationId implementationId(OrganizationId organizationId) {
    return new ProviderImplementationId(
        stableUuid(
            "crewscope:built-in-provider:implementation:v1:",
            implementationKey + ":" + organizationId.value()));
  }

  public ProviderBindingId workspaceBindingId(OrganizationId organizationId, TeamId teamId) {
    return new ProviderBindingId(
        stableUuid(
            "crewscope:built-in-provider:workspace-binding:v1:",
            implementationKey + ":" + organizationId.value() + ":" + teamId.value()));
  }

  /** Restricts a Workspace Binding to the exact Team Workspace resource. */
  public ProviderAccessScope workspaceAccess(WorkspaceId workspaceId) {
    return new ProviderAccessScope(
        capabilities,
        ProviderResourceScope.of(
            "workspace:" + Objects.requireNonNull(workspaceId, "workspaceId")));
  }

  public ProviderDescriptor descriptor() {
    return new ProviderDescriptor(type, implementationKey, interfaceVersion, displayName);
  }

  private static UUID stableUuid(String namespace, Object source) {
    byte[] digest;
    try {
      digest =
          MessageDigest.getInstance("MD5")
              .digest((namespace + source).getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("MD5 is unavailable", impossible);
    }
    ByteBuffer bytes = ByteBuffer.wrap(digest);
    // Keep the raw MD5 bits so PostgreSQL md5(text)::uuid produces the same migration IDs.
    return new UUID(bytes.getLong(), bytes.getLong());
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.strip();
  }
}
