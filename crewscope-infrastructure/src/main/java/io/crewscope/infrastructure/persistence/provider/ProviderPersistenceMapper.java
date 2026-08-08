package io.crewscope.infrastructure.persistence.provider;

import static io.crewscope.infrastructure.persistence.PersistenceMappingSupport.audit;

import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionGrantStatus;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ConnectionStatus;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderBindingTarget;
import io.crewscope.domain.provider.ProviderBindingTargetType;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderCapability;
import io.crewscope.domain.provider.ProviderConnectionRequirement;
import io.crewscope.domain.provider.ProviderDefinition;
import io.crewscope.domain.provider.ProviderDefinitionId;
import io.crewscope.domain.provider.ProviderExecutionIdentity;
import io.crewscope.domain.provider.ProviderImplementation;
import io.crewscope.domain.provider.ProviderImplementationId;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderOwnerType;
import io.crewscope.domain.provider.ProviderRegistrationStatus;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Lossless scalar/JSON mapping for Provider registry and authorization facts. */
@Component
public class ProviderPersistenceMapper {

    ProviderDefinitionEntity toEntity(ProviderDefinition value) {
        ProviderDefinitionEntity row = new ProviderDefinitionEntity();
        row.id = value.id().value();
        row.organizationId = value.organizationId().value();
        row.providerKey = value.key();
        row.providerType = value.type().name();
        row.interfaceVersion = value.interfaceVersion();
        row.displayName = value.displayName();
        row.capabilities = capabilities(value.capabilities());
        row.status = value.status().name();
        row.version = value.version();
        putAudit(row, value.audit());
        return row;
    }

    ProviderDefinition toDomain(ProviderDefinitionEntity row) {
        return ProviderDefinition.reconstitute(
                new ProviderDefinitionId(row.id),
                new OrganizationId(row.organizationId),
                row.providerKey,
                ProviderType.valueOf(row.providerType),
                row.interfaceVersion,
                row.displayName,
                ProviderCapabilities.from(row.capabilities),
                ProviderRegistrationStatus.valueOf(row.status),
                row.version,
                audit(row.createdByPrincipalId, row.createdAt, row.updatedByPrincipalId, row.updatedAt));
    }

    ProviderImplementationEntity toEntity(ProviderImplementation value) {
        ProviderImplementationEntity row = new ProviderImplementationEntity();
        row.id = value.id().value();
        row.organizationId = value.organizationId().value();
        row.providerDefinitionId = value.definitionId().value();
        row.providerType = value.type().name();
        row.definitionInterfaceVersion = value.definitionInterfaceVersion();
        row.implementationKey = value.key();
        row.implementationVersion = value.implementationVersion();
        row.capabilities = capabilities(value.capabilities());
        row.connectionRequirement = value.connectionRequirement().name();
        row.connectorKey = value.connectorKey().orElse(null);
        row.status = value.status().name();
        row.version = value.version();
        putAudit(row, value.audit());
        return row;
    }

    ProviderImplementation toDomain(ProviderImplementationEntity row) {
        return ProviderImplementation.reconstitute(
                new ProviderImplementationId(row.id),
                new OrganizationId(row.organizationId),
                new ProviderDefinitionId(row.providerDefinitionId),
                ProviderType.valueOf(row.providerType),
                row.definitionInterfaceVersion,
                row.implementationKey,
                row.implementationVersion,
                ProviderCapabilities.from(row.capabilities),
                ProviderConnectionRequirement.valueOf(row.connectionRequirement),
                Optional.ofNullable(row.connectorKey),
                ProviderRegistrationStatus.valueOf(row.status),
                row.version,
                audit(row.createdByPrincipalId, row.createdAt, row.updatedByPrincipalId, row.updatedAt));
    }

    ConnectionEntity toEntity(Connection value) {
        ConnectionEntity row = new ConnectionEntity();
        row.id = value.id().value();
        row.organizationId = value.organizationId().value();
        putOwner(row, value.owner());
        row.connectorKey = value.connectorKey();
        row.externalAccountReference = value.externalAccountReference();
        row.credentialId = value.credentialId().value();
        row.status = value.status().name();
        row.expiresAt = value.expiresAt().map(UtcTimestamp::value).orElse(null);
        row.terminalReason = value.terminalReason().orElse(null);
        row.version = value.version();
        putAudit(row, value.audit());
        return row;
    }

    Connection toDomain(ConnectionEntity row) {
        return Connection.reconstitute(
                new ConnectionId(row.id),
                new OrganizationId(row.organizationId),
                owner(row.organizationId, row.ownerType, row.ownerId, row.ownerTeamId, row.ownerUserPrincipalId),
                row.connectorKey,
                row.externalAccountReference,
                new CredentialId(row.credentialId),
                ConnectionStatus.valueOf(row.status),
                Optional.ofNullable(row.expiresAt).map(UtcTimestamp::from),
                Optional.ofNullable(row.terminalReason),
                row.version,
                audit(row.createdByPrincipalId, row.createdAt, row.updatedByPrincipalId, row.updatedAt));
    }

    ConnectionGrantEntity toEntity(ConnectionGrant value) {
        ConnectionGrantEntity row = new ConnectionGrantEntity();
        row.id = value.id().value();
        row.organizationId = value.organizationId().value();
        row.connectionId = value.connectionId().value();
        row.connectionOwnerType = value.connectionOwner().type().name();
        row.connectionOwnerId = value.connectionOwner().ownerId();
        row.granteeType = value.grantee().type().name();
        row.granteeId = value.grantee().ownerId();
        row.granteeTeamId = value.grantee().teamId().map(TeamId::value).orElse(null);
        row.granteeUserPrincipalId = value.grantee().userPrincipalId().map(PrincipalId::value).orElse(null);
        row.grantedCapabilities = capabilities(value.grantedAccess().capabilities());
        row.resourceUnrestricted = value.grantedAccess().resources().unrestricted();
        row.grantedResources = resources(value.grantedAccess().resources());
        row.validFrom = value.validFrom().value();
        row.expiresAt = value.expiresAt().map(UtcTimestamp::value).orElse(null);
        row.status = value.status().name();
        row.terminalReason = value.terminalReason().orElse(null);
        row.version = value.version();
        putAudit(row, value.audit());
        return row;
    }

    ConnectionGrant toDomain(ConnectionGrantEntity row) {
        return ConnectionGrant.reconstitute(
                new ConnectionGrantId(row.id),
                new OrganizationId(row.organizationId),
                new ConnectionId(row.connectionId),
                owner(row.organizationId, row.connectionOwnerType, row.connectionOwnerId, null, null),
                owner(row.organizationId, row.granteeType, row.granteeId, row.granteeTeamId, row.granteeUserPrincipalId),
                access(row.grantedCapabilities, row.resourceUnrestricted, row.grantedResources),
                UtcTimestamp.from(row.validFrom),
                Optional.ofNullable(row.expiresAt).map(UtcTimestamp::from),
                ConnectionGrantStatus.valueOf(row.status),
                Optional.ofNullable(row.terminalReason),
                row.version,
                audit(row.createdByPrincipalId, row.createdAt, row.updatedByPrincipalId, row.updatedAt));
    }

    ProviderBindingEntity toEntity(ProviderBinding value) {
        ProviderBindingEntity row = new ProviderBindingEntity();
        row.id = value.id().value();
        row.organizationId = value.organizationId().value();
        row.teamId = value.target().teamId().value();
        row.workspaceId = value.target().workspaceId().value();
        row.targetType = value.target().type().name();
        row.workProjectId = value.target().workProjectId().map(WorkProjectId::value).orElse(null);
        putOwner(row, value.owner());
        row.providerDefinitionId = value.definitionId().value();
        row.providerDefinitionVersion = value.definitionVersion();
        row.providerType = value.providerType().name();
        row.providerImplementationId = value.implementationId().value();
        row.providerImplementationVersion = value.implementationVersion();
        row.connectionRequirement = value.connectionId().isPresent() ? "REQUIRED" : "NONE";
        row.connectionId = value.connectionId().map(ConnectionId::value).orElse(null);
        row.connectionVersion = value.connectionVersion().orElse(null);
        row.connectionGrantId = value.connectionGrantId().map(ConnectionGrantId::value).orElse(null);
        row.connectionGrantVersion = value.connectionGrantVersion().orElse(null);
        row.executionIdentity = value.executionIdentity().map(Enum::name).orElse(null);
        row.effectiveCapabilities = capabilities(value.effectiveAccess().capabilities());
        row.resourceUnrestricted = value.effectiveAccess().resources().unrestricted();
        row.effectiveResources = resources(value.effectiveAccess().resources());
        row.defaultUsage = value.defaultUsage();
        row.status = value.status().name();
        row.version = value.version();
        putAudit(row, value.audit());
        return row;
    }

    ProviderBinding toDomain(ProviderBindingEntity row) {
        ProviderBindingTarget target = new ProviderBindingTarget(
                new OrganizationId(row.organizationId),
                new TeamId(row.teamId),
                new WorkspaceId(row.workspaceId),
                ProviderBindingTargetType.valueOf(row.targetType),
                Optional.ofNullable(row.workProjectId).map(WorkProjectId::new));
        return ProviderBinding.reconstitute(
                new ProviderBindingId(row.id),
                new OrganizationId(row.organizationId),
                target,
                owner(row.organizationId, row.ownerType, row.ownerId, row.ownerTeamId, row.ownerUserPrincipalId),
                new ProviderDefinitionId(row.providerDefinitionId),
                row.providerDefinitionVersion,
                ProviderType.valueOf(row.providerType),
                new ProviderImplementationId(row.providerImplementationId),
                row.providerImplementationVersion,
                Optional.ofNullable(row.connectionId).map(ConnectionId::new),
                Optional.ofNullable(row.connectionVersion),
                Optional.ofNullable(row.connectionGrantId).map(ConnectionGrantId::new),
                Optional.ofNullable(row.connectionGrantVersion),
                Optional.ofNullable(row.executionIdentity).map(ProviderExecutionIdentity::valueOf),
                access(row.effectiveCapabilities, row.resourceUnrestricted, row.effectiveResources),
                row.defaultUsage,
                ProviderRegistrationStatus.valueOf(row.status),
                row.version,
                audit(row.createdByPrincipalId, row.createdAt, row.updatedByPrincipalId, row.updatedAt));
    }

    private static List<String> capabilities(ProviderCapabilities value) {
        return value.values().stream().map(ProviderCapability::value).sorted().toList();
    }

    private static List<String> resources(ProviderResourceScope value) {
        return value.resources().stream().sorted().toList();
    }

    private static ProviderAccessScope access(
            List<String> capabilities, boolean unrestricted, List<String> resources) {
        Set<String> resourceSet = Set.copyOf(resources);
        return new ProviderAccessScope(
                ProviderCapabilities.from(capabilities),
                new ProviderResourceScope(unrestricted, resourceSet));
    }

    private static ProviderOwner owner(
            UUID organizationId,
            String type,
            UUID ownerId,
            UUID teamId,
            UUID userPrincipalId) {
        ProviderOwnerType ownerType = ProviderOwnerType.valueOf(type);
        Optional<TeamId> resolvedTeam = ownerType == ProviderOwnerType.TEAM
                ? Optional.of(new TeamId(teamId == null ? ownerId : teamId))
                : Optional.empty();
        Optional<PrincipalId> resolvedUser = ownerType == ProviderOwnerType.USER
                ? Optional.of(new PrincipalId(userPrincipalId == null ? ownerId : userPrincipalId))
                : Optional.empty();
        return new ProviderOwner(
                new OrganizationId(organizationId), ownerType, ownerId, resolvedTeam, resolvedUser);
    }

    private static UUID principal(Optional<PrincipalId> value, String field) {
        return value.orElseThrow(() -> new DomainValidationException(field, "must be present")).value();
    }

    private static void putOwner(ConnectionEntity row, ProviderOwner value) {
        row.ownerType = value.type().name();
        row.ownerId = value.ownerId();
        row.ownerTeamId = value.teamId().map(TeamId::value).orElse(null);
        row.ownerUserPrincipalId = value.userPrincipalId().map(PrincipalId::value).orElse(null);
    }

    private static void putOwner(ProviderBindingEntity row, ProviderOwner value) {
        row.ownerType = value.type().name();
        row.ownerId = value.ownerId();
        row.ownerTeamId = value.teamId().map(TeamId::value).orElse(null);
        row.ownerUserPrincipalId = value.userPrincipalId().map(PrincipalId::value).orElse(null);
    }

    private static void putAudit(ProviderDefinitionEntity row, AuditMetadata value) {
        row.createdAt = value.createdAt().value();
        row.createdByPrincipalId = principal(value.createdBy(), "providerDefinition.createdBy");
        row.updatedAt = value.updatedAt().value();
        row.updatedByPrincipalId = principal(value.updatedBy(), "providerDefinition.updatedBy");
    }

    private static void putAudit(ProviderImplementationEntity row, AuditMetadata value) {
        row.createdAt = value.createdAt().value();
        row.createdByPrincipalId = principal(value.createdBy(), "providerImplementation.createdBy");
        row.updatedAt = value.updatedAt().value();
        row.updatedByPrincipalId = principal(value.updatedBy(), "providerImplementation.updatedBy");
    }

    private static void putAudit(ConnectionEntity row, AuditMetadata value) {
        row.createdAt = value.createdAt().value();
        row.createdByPrincipalId = principal(value.createdBy(), "connection.createdBy");
        row.updatedAt = value.updatedAt().value();
        row.updatedByPrincipalId = principal(value.updatedBy(), "connection.updatedBy");
    }

    private static void putAudit(ConnectionGrantEntity row, AuditMetadata value) {
        row.createdAt = value.createdAt().value();
        row.createdByPrincipalId = principal(value.createdBy(), "connectionGrant.createdBy");
        row.updatedAt = value.updatedAt().value();
        row.updatedByPrincipalId = principal(value.updatedBy(), "connectionGrant.updatedBy");
    }

    private static void putAudit(ProviderBindingEntity row, AuditMetadata value) {
        row.createdAt = value.createdAt().value();
        row.createdByPrincipalId = principal(value.createdBy(), "providerBinding.createdBy");
        row.updatedAt = value.updatedAt().value();
        row.updatedByPrincipalId = principal(value.updatedBy(), "providerBinding.updatedBy");
    }
}
