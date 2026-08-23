package io.crewscope.domain.agent;

import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Versioned Agent capability and policy definition published into the trusted template catalog. */
public final class AgentTemplateDefinition {

    private static final Map<AgentTemplateStatus, Set<AgentTemplateStatus>> ALLOWED_TRANSITIONS =
            Map.of(
                    AgentTemplateStatus.ACTIVE,
                    EnumSet.of(AgentTemplateStatus.DISABLED, AgentTemplateStatus.ARCHIVED),
                    AgentTemplateStatus.DISABLED,
                    EnumSet.of(AgentTemplateStatus.ACTIVE, AgentTemplateStatus.ARCHIVED),
                    AgentTemplateStatus.ARCHIVED,
                    EnumSet.noneOf(AgentTemplateStatus.class));

    private final AgentTemplatePublisherScope publisherScope;
    private final AgentTemplateVersion templateVersion;
    private final Optional<AgentTemplateVersion> previousVersion;
    private final AgentRuntimeRole runtimeRole;
    private final Set<AgentOwnershipType> allowedOwnershipTypes;
    private final Set<AgentExecutionScope> allowedExecutionScopes;
    private final AgentTemplateCapabilities capabilities;
    private final AgentTemplatePolicy policy;
    private final AgentTemplateHash contentHash;
    private final AgentTemplateStatus status;
    private final long lifecycleVersion;
    private final AuditMetadata audit;

    private AgentTemplateDefinition(
            AgentTemplatePublisherScope publisherScope,
            AgentTemplateVersion templateVersion,
            Optional<AgentTemplateVersion> previousVersion,
            AgentRuntimeRole runtimeRole,
            Set<AgentOwnershipType> allowedOwnershipTypes,
            Set<AgentExecutionScope> allowedExecutionScopes,
            AgentTemplateCapabilities capabilities,
            AgentTemplatePolicy policy,
            AgentTemplateStatus status,
            long lifecycleVersion,
            AuditMetadata audit,
            AgentTemplateHash expectedContentHash) {
        this.publisherScope = Objects.requireNonNull(publisherScope, "publisherScope");
        this.templateVersion = Objects.requireNonNull(templateVersion, "templateVersion");
        this.previousVersion = requirePreviousVersion(templateVersion, previousVersion);
        this.runtimeRole = Objects.requireNonNull(runtimeRole, "runtimeRole");
        this.allowedOwnershipTypes = requireNonEmpty(
                allowedOwnershipTypes, "agentTemplate.allowedOwnershipTypes");
        this.allowedExecutionScopes = requireNonEmpty(
                allowedExecutionScopes, "agentTemplate.allowedExecutionScopes");
        requirePublisherBoundary(this.publisherScope, this.allowedOwnershipTypes);
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.status = Objects.requireNonNull(status, "status");
        this.lifecycleVersion = requireLifecycleVersion(lifecycleVersion);
        this.audit = Objects.requireNonNull(audit, "audit");
        this.contentHash = calculateContentHash();
        if (expectedContentHash != null && !expectedContentHash.equals(this.contentHash)) {
            throw new DomainValidationException(
                    "agentTemplate.contentHash",
                    "must match the canonical Agent template definition");
        }
    }

    /** Publishes version one of a trusted template key. */
    public static AgentTemplateDefinition publishInitial(
            AgentTemplatePublisherScope publisherScope,
            AgentTemplateKey templateKey,
            AgentRuntimeRole runtimeRole,
            Set<AgentOwnershipType> allowedOwnershipTypes,
            Set<AgentExecutionScope> allowedExecutionScopes,
            AgentTemplateCapabilities capabilities,
            AgentTemplatePolicy policy,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        return new AgentTemplateDefinition(
                publisherScope,
                new AgentTemplateVersion(templateKey, 1),
                Optional.empty(),
                runtimeRole,
                allowedOwnershipTypes,
                allowedExecutionScopes,
                capabilities,
                policy,
                AgentTemplateStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(actor, occurredAt),
                null);
    }

    /** Appends the next immutable version while preserving this historical definition. */
    public AgentTemplateDefinition publishNext(
            Set<AgentOwnershipType> nextAllowedOwnershipTypes,
            Set<AgentExecutionScope> nextAllowedExecutionScopes,
            AgentTemplateCapabilities nextCapabilities,
            AgentTemplatePolicy nextPolicy,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        if (status == AgentTemplateStatus.ARCHIVED) {
            throw new DomainValidationException(
                    "agentTemplate.status", "an archived template key cannot publish a new version");
        }
        return new AgentTemplateDefinition(
                publisherScope,
                templateVersion.next(),
                Optional.of(templateVersion),
                runtimeRole,
                nextAllowedOwnershipTypes,
                nextAllowedExecutionScopes,
                nextCapabilities,
                nextPolicy,
                AgentTemplateStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(actor, occurredAt),
                null);
    }

    /** Reconstitutes one exact definition and verifies all immutable hashes. */
    public static AgentTemplateDefinition reconstitute(
            AgentTemplatePublisherScope publisherScope,
            AgentTemplateVersion templateVersion,
            Optional<AgentTemplateVersion> previousVersion,
            AgentRuntimeRole runtimeRole,
            Set<AgentOwnershipType> allowedOwnershipTypes,
            Set<AgentExecutionScope> allowedExecutionScopes,
            AgentTemplateCapabilities capabilities,
            AgentTemplatePolicy policy,
            AgentTemplateHash contentHash,
            AgentTemplateStatus status,
            long lifecycleVersion,
            AuditMetadata audit) {
        return new AgentTemplateDefinition(
                publisherScope,
                templateVersion,
                previousVersion,
                runtimeRole,
                allowedOwnershipTypes,
                allowedExecutionScopes,
                capabilities,
                policy,
                status,
                lifecycleVersion,
                audit,
                Objects.requireNonNull(contentHash, "contentHash"));
    }

    public AgentTemplateDefinition activate(PrincipalId actor, UtcTimestamp occurredAt) {
        return transitionTo(AgentTemplateStatus.ACTIVE, actor, occurredAt);
    }

    public AgentTemplateDefinition disable(PrincipalId actor, UtcTimestamp occurredAt) {
        return transitionTo(AgentTemplateStatus.DISABLED, actor, occurredAt);
    }

    public AgentTemplateDefinition archive(PrincipalId actor, UtcTimestamp occurredAt) {
        return transitionTo(AgentTemplateStatus.ARCHIVED, actor, occurredAt);
    }

    /** Fails closed unless this active template can create an Agent for the explicit owner. */
    public void requireInstantiable(AgentOwnership ownership) {
        AgentOwnership requiredOwnership = Objects.requireNonNull(ownership, "ownership");
        if (status != AgentTemplateStatus.ACTIVE) {
            throw new DomainValidationException(
                    "agentTemplate.status", "must be ACTIVE to create a new Agent instance");
        }
        requireOwnershipBoundary(requiredOwnership);
    }

    /** Fails closed unless ownership and responsibility scope are both explicitly allowed. */
    public void requireExecutable(
            AgentOwnership ownership, AgentExecutionScope executionScope) {
        AgentOwnership requiredOwnership = Objects.requireNonNull(ownership, "ownership");
        AgentExecutionScope requiredScope = Objects.requireNonNull(
                executionScope, "executionScope");
        requireOwnershipBoundary(requiredOwnership);
        if (!allowedExecutionScopes.contains(requiredScope)) {
            throw new DomainValidationException(
                    "agentExecution.scope",
                    requiredScope + " is not allowed by Agent template " + templateVersion);
        }
    }

    private void requireOwnershipBoundary(AgentOwnership ownership) {
        if (!publisherScope.organizationId().equals(ownership.organizationId())) {
            throw new DomainValidationException(
                    "agentOwnership.organizationId",
                    "must match the Agent template publisher Organization");
        }
        if (!allowedOwnershipTypes.contains(ownership.type())) {
            throw new DomainValidationException(
                    "agentOwnership.type",
                    ownership.type() + " is not allowed by Agent template " + templateVersion);
        }
        publisherScope.teamId().ifPresent(publisherTeamId -> {
            if (ownership.teamId().filter(publisherTeamId::equals).isEmpty()) {
                throw new DomainValidationException(
                        "agentOwnership.teamId",
                        "must match the Team-published Agent template boundary");
            }
        });
    }

    private AgentTemplateDefinition transitionTo(
            AgentTemplateStatus target, PrincipalId actor, UtcTimestamp occurredAt) {
        Objects.requireNonNull(target, "target");
        if (!ALLOWED_TRANSITIONS.get(status).contains(target)) {
            throw new DomainValidationException(
                    "agentTemplate.status",
                    "cannot transition from " + status + " to " + target);
        }
        return new AgentTemplateDefinition(
                publisherScope,
                templateVersion,
                previousVersion,
                runtimeRole,
                allowedOwnershipTypes,
                allowedExecutionScopes,
                capabilities,
                policy,
                target,
                lifecycleVersion + 1,
                audit.modifiedBy(actor, occurredAt),
                contentHash);
    }

    private AgentTemplateHash calculateContentHash() {
        StringBuilder canonical = new StringBuilder("agent-template-definition-v1");
        AgentTemplateHash.append(canonical, publisherScope.organizationId().toString());
        AgentTemplateHash.append(
                canonical, publisherScope.teamId().map(Object::toString).orElse("team:none"));
        AgentTemplateHash.append(canonical, templateVersion.key().toString());
        AgentTemplateHash.append(canonical, Long.toString(templateVersion.version()));
        AgentTemplateHash.append(
                canonical, previousVersion.map(Object::toString).orElse("previous:none"));
        AgentTemplateHash.append(canonical, runtimeRole.name());
        allowedOwnershipTypes.stream()
                .sorted(Comparator.comparing(AgentOwnershipType::name))
                .forEach(value -> AgentTemplateHash.append(canonical, "ownership:" + value));
        allowedExecutionScopes.stream()
                .sorted(Comparator.comparing(AgentExecutionScope::name))
                .forEach(value -> AgentTemplateHash.append(canonical, "scope:" + value));
        AgentTemplateHash.append(canonical, capabilities.capabilityHash().toString());
        AgentTemplateHash.append(canonical, policy.policyHash().toString());
        return AgentTemplateHash.sha256(canonical.toString());
    }

    private static Optional<AgentTemplateVersion> requirePreviousVersion(
            AgentTemplateVersion current,
            Optional<AgentTemplateVersion> previousVersion) {
        Optional<AgentTemplateVersion> requiredPrevious = Objects.requireNonNull(
                previousVersion, "previousVersion");
        if (current.version() == 1 && requiredPrevious.isPresent()) {
            throw new DomainValidationException(
                    "agentTemplate.previousVersion", "must be empty for version one");
        }
        if (current.version() > 1) {
            AgentTemplateVersion previous = requiredPrevious.orElseThrow(() ->
                    new DomainValidationException(
                            "agentTemplate.previousVersion",
                            "is required after version one"));
            if (!previous.key().equals(current.key())
                    || previous.version() != current.version() - 1) {
                throw new DomainValidationException(
                        "agentTemplate.previousVersion",
                        "must reference the immediately preceding version of the same key");
            }
        }
        return requiredPrevious;
    }

    private static <E extends Enum<E>> Set<E> requireNonEmpty(Set<E> values, String field) {
        Set<E> requiredValues = Set.copyOf(Objects.requireNonNull(values, field));
        if (requiredValues.isEmpty()) {
            throw new DomainValidationException(field, "must not be empty");
        }
        return requiredValues;
    }

    private static void requirePublisherBoundary(
            AgentTemplatePublisherScope publisherScope,
            Set<AgentOwnershipType> allowedOwnershipTypes) {
        if (publisherScope.teamId().isPresent()
                && allowedOwnershipTypes.contains(AgentOwnershipType.ORGANIZATION)) {
            throw new DomainValidationException(
                    "agentTemplate.allowedOwnershipTypes",
                    "a Team-published template cannot create ORGANIZATION-owned Agents");
        }
    }

    private static long requireLifecycleVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException(
                    "agentTemplate.lifecycleVersion", "must not be negative");
        }
        return value;
    }

    public AgentTemplatePublisherScope publisherScope() {
        return publisherScope;
    }

    public AgentTemplateVersion templateVersion() {
        return templateVersion;
    }

    public Optional<AgentTemplateVersion> previousVersion() {
        return previousVersion;
    }

    public AgentRuntimeRole runtimeRole() {
        return runtimeRole;
    }

    public Set<AgentOwnershipType> allowedOwnershipTypes() {
        return allowedOwnershipTypes;
    }

    public Set<AgentExecutionScope> allowedExecutionScopes() {
        return allowedExecutionScopes;
    }

    public AgentTemplateCapabilities capabilities() {
        return capabilities;
    }

    public AgentTemplatePolicy policy() {
        return policy;
    }

    public AgentTemplateHash contentHash() {
        return contentHash;
    }

    public AgentTemplateStatus status() {
        return status;
    }

    public long lifecycleVersion() {
        return lifecycleVersion;
    }

    public AuditMetadata audit() {
        return audit;
    }
}
