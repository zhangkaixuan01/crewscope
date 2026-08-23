package io.crewscope.domain.model;

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

/** One exact immutable revision of a stable model catalog entry. */
public final class ModelCatalogEntry {

    public static final int MAX_DISPLAY_NAME_LENGTH = 200;

    private static final Map<ModelRegistryStatus, Set<ModelRegistryStatus>> ALLOWED_TRANSITIONS =
            Map.of(
                    ModelRegistryStatus.ACTIVE,
                    EnumSet.of(ModelRegistryStatus.DISABLED, ModelRegistryStatus.ARCHIVED),
                    ModelRegistryStatus.DISABLED,
                    EnumSet.of(ModelRegistryStatus.ACTIVE, ModelRegistryStatus.ARCHIVED),
                    ModelRegistryStatus.ARCHIVED,
                    EnumSet.noneOf(ModelRegistryStatus.class));

    private final ModelCatalogEntryId id;
    private final ModelProviderKey providerKey;
    private final ModelRegistryHash providerDefinitionHash;
    private final ModelId modelId;
    private final ModelCatalogRevision catalogRevision;
    private final Optional<ModelCatalogRevision> previousRevision;
    private final ModelRevision modelRevision;
    private final String displayName;
    private final long contextWindowTokens;
    private final long maximumOutputTokens;
    private final Set<ModelCapability> capabilities;
    private final Set<ModelRegion> availableRegions;
    private final ModelRegistryHash contentHash;
    private final ModelRegistryStatus status;
    private final long lifecycleVersion;
    private final AuditMetadata audit;

    private ModelCatalogEntry(
            ModelProviderDefinition provider,
            ModelRegistryHash providerDefinitionHash,
            ModelCatalogEntryId id,
            ModelId modelId,
            ModelCatalogRevision catalogRevision,
            Optional<ModelCatalogRevision> previousRevision,
            ModelRevision modelRevision,
            String displayName,
            long contextWindowTokens,
            long maximumOutputTokens,
            Set<ModelCapability> capabilities,
            Set<ModelRegion> availableRegions,
            ModelRegistryStatus status,
            long lifecycleVersion,
            AuditMetadata audit,
            ModelRegistryHash expectedContentHash,
            boolean requireActiveProvider) {
        ModelProviderDefinition requiredProvider = Objects.requireNonNull(provider, "provider");
        if (requireActiveProvider) {
            requiredProvider.requireSelectable();
        }
        this.id = Objects.requireNonNull(id, "id");
        this.providerKey = requiredProvider.providerKey();
        this.providerDefinitionHash = Objects.requireNonNull(
                providerDefinitionHash, "providerDefinitionHash");
        if (!this.providerDefinitionHash.equals(requiredProvider.contentHash())) {
            throw new DomainValidationException(
                    "modelCatalog.providerDefinitionHash",
                    "must match the exact model provider definition");
        }
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.catalogRevision = Objects.requireNonNull(catalogRevision, "catalogRevision");
        this.previousRevision = requirePreviousRevision(catalogRevision, previousRevision);
        this.modelRevision = Objects.requireNonNull(modelRevision, "modelRevision");
        this.displayName = requireDisplayName(displayName);
        this.contextWindowTokens = requirePositiveTokenLimit(
                contextWindowTokens, "modelCatalog.contextWindowTokens");
        this.maximumOutputTokens = requirePositiveTokenLimit(
                maximumOutputTokens, "modelCatalog.maximumOutputTokens");
        if (this.maximumOutputTokens > this.contextWindowTokens) {
            throw new DomainValidationException(
                    "modelCatalog.maximumOutputTokens",
                    "must not exceed the context window");
        }
        this.capabilities = requireCapabilities(capabilities);
        this.availableRegions = requireRegions(requiredProvider, availableRegions);
        this.status = Objects.requireNonNull(status, "status");
        this.lifecycleVersion = requireLifecycleVersion(lifecycleVersion);
        this.audit = Objects.requireNonNull(audit, "audit");
        this.contentHash = calculateContentHash();
        if (expectedContentHash != null && !expectedContentHash.equals(this.contentHash)) {
            throw new DomainValidationException(
                    "modelCatalog.contentHash",
                    "must match the canonical model catalog revision");
        }
    }

    private ModelCatalogEntry(
            ModelCatalogEntry source,
            ModelRegistryStatus status,
            long lifecycleVersion,
            AuditMetadata audit) {
        this.id = source.id;
        this.providerKey = source.providerKey;
        this.providerDefinitionHash = source.providerDefinitionHash;
        this.modelId = source.modelId;
        this.catalogRevision = source.catalogRevision;
        this.previousRevision = source.previousRevision;
        this.modelRevision = source.modelRevision;
        this.displayName = source.displayName;
        this.contextWindowTokens = source.contextWindowTokens;
        this.maximumOutputTokens = source.maximumOutputTokens;
        this.capabilities = source.capabilities;
        this.availableRegions = source.availableRegions;
        this.contentHash = source.contentHash;
        this.status = Objects.requireNonNull(status, "status");
        this.lifecycleVersion = requireLifecycleVersion(lifecycleVersion);
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Publishes revision one for a stable provider model entry. */
    public static ModelCatalogEntry publishInitial(
            ModelProviderDefinition provider,
            ModelCatalogEntryId id,
            ModelId modelId,
            ModelRevision modelRevision,
            String displayName,
            long contextWindowTokens,
            long maximumOutputTokens,
            Set<ModelCapability> capabilities,
            Set<ModelRegion> availableRegions,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        return new ModelCatalogEntry(
                provider,
                Objects.requireNonNull(provider, "provider").contentHash(),
                id,
                modelId,
                new ModelCatalogRevision(1),
                Optional.empty(),
                modelRevision,
                displayName,
                contextWindowTokens,
                maximumOutputTokens,
                capabilities,
                availableRegions,
                ModelRegistryStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(actor, occurredAt),
                null,
                true);
    }

    /** Appends the next immutable catalog revision while preserving stable identity. */
    public ModelCatalogEntry publishNext(
            ModelProviderDefinition provider,
            ModelRevision nextModelRevision,
            String nextDisplayName,
            long nextContextWindowTokens,
            long nextMaximumOutputTokens,
            Set<ModelCapability> nextCapabilities,
            Set<ModelRegion> nextAvailableRegions,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        if (status == ModelRegistryStatus.ARCHIVED) {
            throw new DomainValidationException(
                    "modelCatalog.status", "an archived catalog entry cannot publish a revision");
        }
        requireSameProvider(provider, true);
        return new ModelCatalogEntry(
                provider,
                providerDefinitionHash,
                id,
                modelId,
                catalogRevision.next(),
                Optional.of(catalogRevision),
                nextModelRevision,
                nextDisplayName,
                nextContextWindowTokens,
                nextMaximumOutputTokens,
                nextCapabilities,
                nextAvailableRegions,
                ModelRegistryStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(actor, occurredAt),
                null,
                true);
    }

    /** Reconstitutes one exact catalog revision and verifies provider and content coordinates. */
    public static ModelCatalogEntry reconstitute(
            ModelProviderDefinition provider,
            ModelRegistryHash providerDefinitionHash,
            ModelCatalogEntryId id,
            ModelId modelId,
            ModelCatalogRevision catalogRevision,
            Optional<ModelCatalogRevision> previousRevision,
            ModelRevision modelRevision,
            String displayName,
            long contextWindowTokens,
            long maximumOutputTokens,
            Set<ModelCapability> capabilities,
            Set<ModelRegion> availableRegions,
            ModelRegistryHash contentHash,
            ModelRegistryStatus status,
            long lifecycleVersion,
            AuditMetadata audit) {
        return new ModelCatalogEntry(
                provider,
                providerDefinitionHash,
                id,
                modelId,
                catalogRevision,
                previousRevision,
                modelRevision,
                displayName,
                contextWindowTokens,
                maximumOutputTokens,
                capabilities,
                availableRegions,
                status,
                lifecycleVersion,
                audit,
                Objects.requireNonNull(contentHash, "contentHash"),
                false);
    }

    public ModelCatalogEntry activate(PrincipalId actor, UtcTimestamp occurredAt) {
        return transitionTo(ModelRegistryStatus.ACTIVE, actor, occurredAt);
    }

    public ModelCatalogEntry disable(PrincipalId actor, UtcTimestamp occurredAt) {
        return transitionTo(ModelRegistryStatus.DISABLED, actor, occurredAt);
    }

    public ModelCatalogEntry archive(PrincipalId actor, UtcTimestamp occurredAt) {
        return transitionTo(ModelRegistryStatus.ARCHIVED, actor, occurredAt);
    }

    /** Validates the current provider definition and active catalog state for new selection. */
    public void requireSelectable(ModelProviderDefinition provider) {
        requireSameProvider(provider, true);
        if (status != ModelRegistryStatus.ACTIVE) {
            throw new DomainValidationException(
                    "modelCatalog.status", "must be ACTIVE for a new model selection");
        }
    }

    private void requireSameProvider(
            ModelProviderDefinition provider, boolean requireActiveProvider) {
        ModelProviderDefinition requiredProvider = Objects.requireNonNull(provider, "provider");
        if (requireActiveProvider) {
            requiredProvider.requireSelectable();
        }
        if (!providerKey.equals(requiredProvider.providerKey())
                || !providerDefinitionHash.equals(requiredProvider.contentHash())) {
            throw new DomainValidationException(
                    "modelCatalog.providerKey",
                    "must reference the exact model provider definition");
        }
    }

    private ModelCatalogEntry transitionTo(
            ModelRegistryStatus target, PrincipalId actor, UtcTimestamp occurredAt) {
        Objects.requireNonNull(target, "target");
        if (!ALLOWED_TRANSITIONS.get(status).contains(target)) {
            throw new DomainValidationException(
                    "modelCatalog.status",
                    "cannot transition from " + status + " to " + target);
        }
        return new ModelCatalogEntry(
                this,
                target,
                lifecycleVersion + 1,
                audit.modifiedBy(actor, occurredAt));
    }

    private ModelRegistryHash calculateContentHash() {
        StringBuilder canonical = new StringBuilder("model-catalog-entry-v1");
        ModelRegistryHash.append(canonical, id.toString());
        ModelRegistryHash.append(canonical, providerKey.toString());
        ModelRegistryHash.append(canonical, providerDefinitionHash.toString());
        ModelRegistryHash.append(canonical, modelId.toString());
        ModelRegistryHash.append(canonical, catalogRevision.toString());
        ModelRegistryHash.append(
                canonical, previousRevision.map(Object::toString).orElse("previous:none"));
        ModelRegistryHash.append(canonical, modelRevision.toString());
        ModelRegistryHash.append(canonical, displayName);
        ModelRegistryHash.append(canonical, Long.toString(contextWindowTokens));
        ModelRegistryHash.append(canonical, Long.toString(maximumOutputTokens));
        capabilities.stream()
                .sorted(Comparator.naturalOrder())
                .forEach(value -> ModelRegistryHash.append(canonical, "capability:" + value));
        availableRegions.stream()
                .sorted(Comparator.naturalOrder())
                .forEach(value -> ModelRegistryHash.append(canonical, "region:" + value));
        return ModelRegistryHash.sha256(canonical.toString());
    }

    private static Optional<ModelCatalogRevision> requirePreviousRevision(
            ModelCatalogRevision current,
            Optional<ModelCatalogRevision> previousRevision) {
        Optional<ModelCatalogRevision> requiredPrevious = Objects.requireNonNull(
                previousRevision, "previousRevision");
        if (current.value() == 1 && requiredPrevious.isPresent()) {
            throw new DomainValidationException(
                    "modelCatalog.previousRevision", "must be empty for revision one");
        }
        if (current.value() > 1) {
            ModelCatalogRevision previous = requiredPrevious.orElseThrow(() ->
                    new DomainValidationException(
                            "modelCatalog.previousRevision",
                            "is required after revision one"));
            if (previous.value() != current.value() - 1) {
                throw new DomainValidationException(
                        "modelCatalog.previousRevision",
                        "must reference the immediately preceding catalog revision");
            }
        }
        return requiredPrevious;
    }

    private static String requireDisplayName(String value) {
        if (value == null
                || value.isBlank()
                || value.strip().length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new DomainValidationException(
                    "modelCatalog.displayName", "must be non-blank and at most 200 characters");
        }
        return value.strip();
    }

    private static long requirePositiveTokenLimit(long value, String field) {
        if (value < 1) {
            throw new DomainValidationException(field, "must be positive");
        }
        return value;
    }

    private static Set<ModelCapability> requireCapabilities(Set<ModelCapability> values) {
        Set<ModelCapability> required = Set.copyOf(Objects.requireNonNull(values, "capabilities"));
        if (required.isEmpty()) {
            throw new DomainValidationException(
                    "modelCatalog.capabilities", "must not be empty");
        }
        return required;
    }

    private static Set<ModelRegion> requireRegions(
            ModelProviderDefinition provider, Set<ModelRegion> values) {
        Set<ModelRegion> required = Set.copyOf(Objects.requireNonNull(values, "availableRegions"));
        if (required.isEmpty() || !provider.availableRegions().containsAll(required)) {
            throw new DomainValidationException(
                    "modelCatalog.availableRegions",
                    "must be a non-empty subset of the provider regions");
        }
        return required;
    }

    private static long requireLifecycleVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException(
                    "modelCatalog.lifecycleVersion", "must not be negative");
        }
        return value;
    }

    public ModelCatalogCoordinate coordinate() {
        return new ModelCatalogCoordinate(id, providerKey, modelId, catalogRevision);
    }

    public ModelCatalogEntryId id() {
        return id;
    }

    public ModelProviderKey providerKey() {
        return providerKey;
    }

    public ModelRegistryHash providerDefinitionHash() {
        return providerDefinitionHash;
    }

    public ModelId modelId() {
        return modelId;
    }

    public ModelCatalogRevision catalogRevision() {
        return catalogRevision;
    }

    public Optional<ModelCatalogRevision> previousRevision() {
        return previousRevision;
    }

    public ModelRevision modelRevision() {
        return modelRevision;
    }

    public String displayName() {
        return displayName;
    }

    public long contextWindowTokens() {
        return contextWindowTokens;
    }

    public long maximumOutputTokens() {
        return maximumOutputTokens;
    }

    public Set<ModelCapability> capabilities() {
        return capabilities;
    }

    public Set<ModelRegion> availableRegions() {
        return availableRegions;
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
