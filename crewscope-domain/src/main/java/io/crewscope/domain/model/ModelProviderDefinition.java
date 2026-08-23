package io.crewscope.domain.model;

import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Trusted product provider definition kept separate from its AgentScope adapter. */
public final class ModelProviderDefinition {

    public static final int MAX_DISPLAY_NAME_LENGTH = 200;

    private static final Map<ModelRegistryStatus, Set<ModelRegistryStatus>> ALLOWED_TRANSITIONS =
            Map.of(
                    ModelRegistryStatus.ACTIVE,
                    EnumSet.of(ModelRegistryStatus.DISABLED, ModelRegistryStatus.ARCHIVED),
                    ModelRegistryStatus.DISABLED,
                    EnumSet.of(ModelRegistryStatus.ACTIVE, ModelRegistryStatus.ARCHIVED),
                    ModelRegistryStatus.ARCHIVED,
                    EnumSet.noneOf(ModelRegistryStatus.class));

    private final ModelProviderKey providerKey;
    private final String displayName;
    private final ModelAdapterKey adapterKey;
    private final ModelEndpoint defaultEndpoint;
    private final Set<ModelRegion> availableRegions;
    private final ModelDataPolicy dataPolicy;
    private final ModelRegistryHash contentHash;
    private final ModelRegistryStatus status;
    private final long lifecycleVersion;
    private final AuditMetadata audit;

    private ModelProviderDefinition(
            ModelProviderKey providerKey,
            String displayName,
            ModelAdapterKey adapterKey,
            ModelEndpoint defaultEndpoint,
            Set<ModelRegion> availableRegions,
            ModelDataPolicy dataPolicy,
            ModelRegistryStatus status,
            long lifecycleVersion,
            AuditMetadata audit,
            ModelRegistryHash expectedContentHash) {
        this.providerKey = Objects.requireNonNull(providerKey, "providerKey");
        this.displayName = requireDisplayName(displayName);
        this.adapterKey = Objects.requireNonNull(adapterKey, "adapterKey");
        this.defaultEndpoint = Objects.requireNonNull(defaultEndpoint, "defaultEndpoint");
        this.availableRegions = requireRegions(availableRegions);
        this.dataPolicy = Objects.requireNonNull(dataPolicy, "dataPolicy");
        this.status = Objects.requireNonNull(status, "status");
        this.lifecycleVersion = requireLifecycleVersion(lifecycleVersion);
        this.audit = Objects.requireNonNull(audit, "audit");
        this.contentHash = calculateContentHash();
        if (expectedContentHash != null && !expectedContentHash.equals(this.contentHash)) {
            throw new DomainValidationException(
                    "modelProvider.contentHash",
                    "must match the canonical provider definition");
        }
    }

    /** Publishes an active trusted provider definition. */
    public static ModelProviderDefinition publish(
            ModelProviderKey providerKey,
            String displayName,
            ModelAdapterKey adapterKey,
            ModelEndpoint defaultEndpoint,
            Set<ModelRegion> availableRegions,
            ModelDataPolicy dataPolicy,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        return new ModelProviderDefinition(
                providerKey,
                displayName,
                adapterKey,
                defaultEndpoint,
                availableRegions,
                dataPolicy,
                ModelRegistryStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(actor, occurredAt),
                null);
    }

    /** Reconstitutes one provider and verifies its immutable content hash. */
    public static ModelProviderDefinition reconstitute(
            ModelProviderKey providerKey,
            String displayName,
            ModelAdapterKey adapterKey,
            ModelEndpoint defaultEndpoint,
            Set<ModelRegion> availableRegions,
            ModelDataPolicy dataPolicy,
            ModelRegistryHash contentHash,
            ModelRegistryStatus status,
            long lifecycleVersion,
            AuditMetadata audit) {
        return new ModelProviderDefinition(
                providerKey,
                displayName,
                adapterKey,
                defaultEndpoint,
                availableRegions,
                dataPolicy,
                status,
                lifecycleVersion,
                audit,
                Objects.requireNonNull(contentHash, "contentHash"));
    }

    public ModelProviderDefinition activate(PrincipalId actor, UtcTimestamp occurredAt) {
        return transitionTo(ModelRegistryStatus.ACTIVE, actor, occurredAt);
    }

    public ModelProviderDefinition disable(PrincipalId actor, UtcTimestamp occurredAt) {
        return transitionTo(ModelRegistryStatus.DISABLED, actor, occurredAt);
    }

    public ModelProviderDefinition archive(PrincipalId actor, UtcTimestamp occurredAt) {
        return transitionTo(ModelRegistryStatus.ARCHIVED, actor, occurredAt);
    }

    /** Fails closed when this provider cannot participate in a new model selection. */
    public void requireSelectable() {
        if (status != ModelRegistryStatus.ACTIVE) {
            throw new DomainValidationException(
                    "modelProvider.status", "must be ACTIVE for a new model selection");
        }
    }

    private ModelProviderDefinition transitionTo(
            ModelRegistryStatus target, PrincipalId actor, UtcTimestamp occurredAt) {
        Objects.requireNonNull(target, "target");
        if (!ALLOWED_TRANSITIONS.get(status).contains(target)) {
            throw new DomainValidationException(
                    "modelProvider.status",
                    "cannot transition from " + status + " to " + target);
        }
        return new ModelProviderDefinition(
                providerKey,
                displayName,
                adapterKey,
                defaultEndpoint,
                availableRegions,
                dataPolicy,
                target,
                lifecycleVersion + 1,
                audit.modifiedBy(actor, occurredAt),
                contentHash);
    }

    private ModelRegistryHash calculateContentHash() {
        StringBuilder canonical = new StringBuilder("model-provider-definition-v1");
        ModelRegistryHash.append(canonical, providerKey.toString());
        ModelRegistryHash.append(canonical, displayName);
        ModelRegistryHash.append(canonical, adapterKey.toString());
        ModelRegistryHash.append(canonical, defaultEndpoint.toString());
        availableRegions.stream()
                .sorted(Comparator.naturalOrder())
                .forEach(region -> ModelRegistryHash.append(canonical, "region:" + region));
        ModelRegistryHash.append(canonical, dataPolicy.canonicalValue());
        return ModelRegistryHash.sha256(canonical.toString());
    }

    private static String requireDisplayName(String value) {
        if (value == null
                || value.isBlank()
                || value.strip().length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new DomainValidationException(
                    "modelProvider.displayName", "must be non-blank and at most 200 characters");
        }
        return value.strip();
    }

    private static Set<ModelRegion> requireRegions(Set<ModelRegion> values) {
        Set<ModelRegion> required = Set.copyOf(Objects.requireNonNull(values, "availableRegions"));
        if (required.isEmpty()) {
            throw new DomainValidationException(
                    "modelProvider.availableRegions", "must not be empty");
        }
        return required;
    }

    private static long requireLifecycleVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException(
                    "modelProvider.lifecycleVersion", "must not be negative");
        }
        return value;
    }

    public ModelProviderKey providerKey() {
        return providerKey;
    }

    public String displayName() {
        return displayName;
    }

    public ModelAdapterKey adapterKey() {
        return adapterKey;
    }

    public ModelEndpoint defaultEndpoint() {
        return defaultEndpoint;
    }

    public Set<ModelRegion> availableRegions() {
        return availableRegions;
    }

    public ModelDataPolicy dataPolicy() {
        return dataPolicy;
    }

    public ModelRegistryHash contentHash() {
        return contentHash;
    }

    public ModelRegistryStatus status() {
        return status;
    }

    public long lifecycleVersion() {
        return lifecycleVersion;
    }

    public AuditMetadata audit() {
        return audit;
    }
}
