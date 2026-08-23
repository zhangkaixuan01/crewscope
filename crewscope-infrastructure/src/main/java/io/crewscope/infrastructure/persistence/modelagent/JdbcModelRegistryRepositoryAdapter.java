package io.crewscope.infrastructure.persistence.modelagent;

import io.crewscope.application.model.ModelCatalogEntryRepository;
import io.crewscope.application.model.ModelPriceScheduleRepository;
import io.crewscope.application.model.ModelProviderDefinitionRepository;
import io.crewscope.domain.model.ModelCatalogCoordinate;
import io.crewscope.domain.model.ModelCatalogEntry;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelPriceRevision;
import io.crewscope.domain.model.ModelPriceSchedule;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL adapter for trusted provider, catalog and append-only price registry facts. */
@Repository
public class JdbcModelRegistryRepositoryAdapter
        implements ModelProviderDefinitionRepository,
                ModelCatalogEntryRepository,
                ModelPriceScheduleRepository {

    private static final String PROVIDER_SELECT =
            "SELECT * FROM crewscope.model_provider_definition";
    private static final String CATALOG_SELECT =
            "SELECT * FROM crewscope.model_catalog_entry";
    private static final String PRICE_SELECT =
            "SELECT * FROM crewscope.model_price_revision";

    private final NamedParameterJdbcTemplate jdbc;
    private final ModelAgentPersistenceMapper mapper;

    public JdbcModelRegistryRepositoryAdapter(
            NamedParameterJdbcTemplate jdbc, ModelAgentPersistenceMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public ModelProviderDefinition register(ModelProviderDefinition definition) {
        ModelProviderDefinition required = Objects.requireNonNull(definition, "definition");
        if (required.lifecycleVersion() != 0) {
            throw new DomainValidationException(
                    "modelProvider.lifecycleVersion", "must be zero when registered");
        }
        try {
            jdbc.update(
                    """
                    INSERT INTO crewscope.model_provider_definition (
                        provider_key, display_name, adapter_key, default_endpoint,
                        available_regions, retention_mode, maximum_retention_seconds,
                        training_usage_policy, content_hash, status, lifecycle_version,
                        created_at, created_by_principal_id, updated_at, updated_by_principal_id
                    ) VALUES (
                        :providerKey, :displayName, :adapterKey, :defaultEndpoint,
                        :availableRegions, :retentionMode, :maximumRetentionSeconds,
                        :trainingUsagePolicy, :contentHash, :status, :lifecycleVersion,
                        :createdAt, :createdBy, :updatedAt, :updatedBy
                    )
                    """,
                    providerParameters(required));
        } catch (DataIntegrityViolationException failure) {
            throw conflict("modelProvider.providerKey", failure);
        }
        return findByKey(required.providerKey()).orElseThrow();
    }

    @Override
    @Transactional
    public ModelProviderDefinition updateLifecycle(ModelProviderDefinition definition) {
        ModelProviderDefinition required = Objects.requireNonNull(definition, "definition");
        long expected = previous(required.lifecycleVersion(), "modelProvider.lifecycleVersion");
        int affected = jdbc.update(
                """
                UPDATE crewscope.model_provider_definition
                   SET status = :status,
                       lifecycle_version = :lifecycleVersion,
                       updated_at = :updatedAt,
                       updated_by_principal_id = :updatedBy
                 WHERE provider_key = :providerKey
                   AND content_hash = :contentHash
                   AND lifecycle_version = :expectedVersion
                """,
                providerParameters(required).addValue("expectedVersion", expected));
        requireLifecycleUpdated(
                "ModelProviderDefinition", required.providerKey().toString(), expected, affected,
                "SELECT lifecycle_version FROM crewscope.model_provider_definition WHERE provider_key = :key",
                new MapSqlParameterSource("key", required.providerKey().value()));
        return findByKey(required.providerKey()).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ModelProviderDefinition> findByKey(ModelProviderKey providerKey) {
        return jdbc.query(
                        PROVIDER_SELECT + " WHERE provider_key = :providerKey",
                        new MapSqlParameterSource(
                                "providerKey", Objects.requireNonNull(providerKey).value()),
                        (row, ignored) -> mapper.provider(row))
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModelProviderDefinition> findPage(int offset, int limit) {
        ModelAgentJdbcGuard.requirePage(offset, limit, "modelProvider.page");
        return jdbc.query(
                PROVIDER_SELECT + " ORDER BY provider_key OFFSET :offset LIMIT :limit",
                new MapSqlParameterSource().addValue("offset", offset).addValue("limit", limit),
                (row, ignored) -> mapper.provider(row));
    }

    @Override
    @Transactional
    public ModelCatalogEntry append(ModelCatalogEntry entry) {
        ModelCatalogEntry required = Objects.requireNonNull(entry, "entry");
        String stream = required.providerKey() + "/" + required.modelId();
        ModelAgentJdbcGuard.lock(jdbc, "crewscope:model-catalog:" + stream);
        Long latest = jdbc.query(
                        """
                        SELECT catalog_revision FROM crewscope.model_catalog_entry
                         WHERE provider_key = :providerKey AND model_id = :modelId
                         ORDER BY catalog_revision DESC LIMIT 1
                        """,
                        new MapSqlParameterSource()
                                .addValue("providerKey", required.providerKey().value())
                                .addValue("modelId", required.modelId().value()),
                        (row, ignored) -> row.getLong(1))
                .stream().findFirst().orElse(null);
        ModelAgentJdbcGuard.requireNextRevision(
                "modelCatalog.catalogRevision", required.catalogRevision().value(), latest);
        try {
            jdbc.update(
                    """
                    INSERT INTO crewscope.model_catalog_entry (
                        id, provider_key, provider_definition_hash, model_id,
                        catalog_revision, previous_catalog_revision, model_revision,
                        display_name, context_window_tokens, maximum_output_tokens,
                        capabilities, available_regions, content_hash, status, lifecycle_version,
                        created_at, created_by_principal_id, updated_at, updated_by_principal_id
                    ) VALUES (
                        :id, :providerKey, :providerDefinitionHash, :modelId,
                        :catalogRevision, :previousCatalogRevision, :modelRevision,
                        :displayName, :contextWindowTokens, :maximumOutputTokens,
                        :capabilities, :availableRegions, :contentHash, :status, :lifecycleVersion,
                        :createdAt, :createdBy, :updatedAt, :updatedBy
                    )
                    """,
                    catalogParameters(required));
        } catch (DataIntegrityViolationException failure) {
            throw conflict("modelCatalog.catalogRevision", failure);
        }
        return findByCoordinate(required.coordinate()).orElseThrow();
    }

    @Override
    @Transactional
    public ModelCatalogEntry updateLifecycle(ModelCatalogEntry entry) {
        ModelCatalogEntry required = Objects.requireNonNull(entry, "entry");
        long expected = previous(required.lifecycleVersion(), "modelCatalog.lifecycleVersion");
        int affected = jdbc.update(
                """
                UPDATE crewscope.model_catalog_entry
                   SET status = :status,
                       lifecycle_version = :lifecycleVersion,
                       updated_at = :updatedAt,
                       updated_by_principal_id = :updatedBy
                 WHERE id = :id AND provider_key = :providerKey AND model_id = :modelId
                   AND catalog_revision = :catalogRevision
                   AND content_hash = :contentHash
                   AND lifecycle_version = :expectedVersion
                """,
                catalogParameters(required).addValue("expectedVersion", expected));
        requireLifecycleUpdated(
                "ModelCatalogEntry", required.id().toString(), expected, affected,
                "SELECT lifecycle_version FROM crewscope.model_catalog_entry WHERE id = :key AND catalog_revision = :revision",
                new MapSqlParameterSource("key", required.id().value())
                        .addValue("revision", required.catalogRevision().value()));
        return findByCoordinate(required.coordinate()).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ModelCatalogEntry> findByCoordinate(ModelCatalogCoordinate coordinate) {
        ModelCatalogCoordinate required = Objects.requireNonNull(coordinate, "coordinate");
        ModelProviderDefinition provider = findByKey(required.providerKey()).orElse(null);
        if (provider == null) {
            return Optional.empty();
        }
        return jdbc.query(
                        CATALOG_SELECT + """
                         WHERE id = :id AND provider_key = :providerKey
                           AND model_id = :modelId AND catalog_revision = :catalogRevision
                        """,
                        catalogCoordinateParameters(required),
                        (row, ignored) -> mapper.catalog(row, provider))
                .stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ModelCatalogEntry> findLatest(ModelProviderKey providerKey, ModelId modelId) {
        ModelProviderDefinition provider = findByKey(Objects.requireNonNull(providerKey)).orElse(null);
        if (provider == null) {
            return Optional.empty();
        }
        return jdbc.query(
                        CATALOG_SELECT + """
                         WHERE provider_key = :providerKey AND model_id = :modelId
                         ORDER BY catalog_revision DESC LIMIT 1
                        """,
                        new MapSqlParameterSource()
                                .addValue("providerKey", providerKey.value())
                                .addValue("modelId", Objects.requireNonNull(modelId).value()),
                        (row, ignored) -> mapper.catalog(row, provider))
                .stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModelCatalogEntry> findPage(
            ModelProviderKey providerKey, int offset, int limit) {
        ModelAgentJdbcGuard.requirePage(offset, limit, "modelCatalog.page");
        ModelProviderDefinition provider = findByKey(Objects.requireNonNull(providerKey))
                .orElseThrow(() -> new DomainValidationException(
                        "modelCatalog.providerKey", "must reference a registered provider"));
        return jdbc.query(
                CATALOG_SELECT + """
                 WHERE provider_key = :providerKey
                 ORDER BY model_id, catalog_revision DESC, id
                 OFFSET :offset LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("providerKey", providerKey.value())
                        .addValue("offset", offset)
                        .addValue("limit", limit),
                (row, ignored) -> mapper.catalog(row, provider));
    }

    @Override
    @Transactional
    public ModelPriceRevision append(ModelPriceRevision priceRevision) {
        ModelPriceRevision required = Objects.requireNonNull(priceRevision, "priceRevision");
        ModelCatalogCoordinate coordinate = required.catalogCoordinate();
        ModelAgentJdbcGuard.lock(jdbc, "crewscope:model-price:" + coordinate);
        PriceHead head = jdbc.query(
                        """
                        SELECT price_revision, effective_from
                          FROM crewscope.model_price_revision
                         WHERE catalog_entry_id = :catalogEntryId
                           AND provider_key = :providerKey AND model_id = :modelId
                           AND catalog_revision = :catalogRevision
                         ORDER BY price_revision DESC LIMIT 1
                        """,
                        catalogCoordinateParameters(coordinate),
                        (row, ignored) -> new PriceHead(
                                row.getLong("price_revision"),
                                UtcTimestamp.from(row.getObject("effective_from", OffsetDateTime.class).toInstant())))
                .stream().findFirst().orElse(null);
        ModelAgentJdbcGuard.requireNextRevision(
                "modelPrice.revision", required.revision(), head == null ? null : head.revision());
        if (head != null && required.effectiveFrom().compareTo(head.effectiveFrom()) <= 0) {
            throw new DomainValidationException(
                    "modelPrice.effectiveFrom", "must be later than the committed latest price");
        }
        try {
            jdbc.update(
                    """
                    INSERT INTO crewscope.model_price_revision (
                        catalog_entry_id, provider_key, model_id, catalog_revision,
                        price_revision, effective_from, input_per_million_tokens,
                        output_per_million_tokens, cached_input_per_million_tokens,
                        currency_code, price_source, content_hash, created_at,
                        created_by_principal_id
                    ) VALUES (
                        :catalogEntryId, :providerKey, :modelId, :catalogRevision,
                        :priceRevision, :effectiveFrom, :inputPrice,
                        :outputPrice, :cachedInputPrice,
                        :currencyCode, :priceSource, :contentHash, :createdAt,
                        :createdBy
                    )
                    """,
                    priceParameters(required));
        } catch (DataIntegrityViolationException failure) {
            throw conflict("modelPrice.revision", failure);
        }
        return findPrice(coordinate, required.revision()).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ModelPriceSchedule> findSchedule(ModelCatalogCoordinate coordinate) {
        ModelCatalogCoordinate required = Objects.requireNonNull(coordinate, "coordinate");
        List<ModelPriceRevision> revisions = jdbc.query(
                PRICE_SELECT + """
                 WHERE catalog_entry_id = :catalogEntryId
                   AND provider_key = :providerKey AND model_id = :modelId
                   AND catalog_revision = :catalogRevision
                 ORDER BY price_revision
                """,
                catalogCoordinateParameters(required),
                (row, ignored) -> mapper.price(row));
        return revisions.isEmpty()
                ? Optional.empty()
                : Optional.of(ModelPriceSchedule.reconstitute(required, revisions));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ModelPriceRevision> findEffectivePrice(
            ModelCatalogCoordinate coordinate, UtcTimestamp effectiveAt) {
        ModelCatalogCoordinate required = Objects.requireNonNull(coordinate, "coordinate");
        return jdbc.query(
                        PRICE_SELECT + """
                         WHERE catalog_entry_id = :catalogEntryId
                           AND provider_key = :providerKey AND model_id = :modelId
                           AND catalog_revision = :catalogRevision
                           AND effective_from <= :effectiveAt
                         ORDER BY effective_from DESC, price_revision DESC LIMIT 1
                        """,
                        catalogCoordinateParameters(required).addValue(
                                "effectiveAt", timestamp(Objects.requireNonNull(effectiveAt))),
                        (row, ignored) -> mapper.price(row))
                .stream().findFirst();
    }

    private Optional<ModelPriceRevision> findPrice(
            ModelCatalogCoordinate coordinate, long revision) {
        return jdbc.query(
                        PRICE_SELECT + """
                         WHERE catalog_entry_id = :catalogEntryId
                           AND provider_key = :providerKey AND model_id = :modelId
                           AND catalog_revision = :catalogRevision
                           AND price_revision = :revision
                        """,
                        catalogCoordinateParameters(coordinate)
                                .addValue("revision", revision),
                        (row, ignored) -> mapper.price(row))
                .stream().findFirst();
    }

    private MapSqlParameterSource providerParameters(ModelProviderDefinition value) {
        PrincipalId createdBy = value.audit().createdBy().orElseThrow();
        PrincipalId updatedBy = value.audit().updatedBy().orElseThrow();
        return new MapSqlParameterSource()
                .addValue("providerKey", value.providerKey().value())
                .addValue("displayName", value.displayName())
                .addValue("adapterKey", value.adapterKey().value())
                .addValue("defaultEndpoint", value.defaultEndpoint().value())
                .addValue("availableRegions", mapper.jsonb(value.availableRegions().stream()
                        .map(ModelRegion::value).sorted().toList()))
                .addValue("retentionMode", value.dataPolicy().retentionMode().name())
                .addValue("maximumRetentionSeconds", value.dataPolicy().maximumRetention()
                        .map(java.time.Duration::getSeconds).orElse(null))
                .addValue("trainingUsagePolicy", value.dataPolicy().trainingUsagePolicy().name())
                .addValue("contentHash", value.contentHash().value())
                .addValue("status", value.status().name())
                .addValue("lifecycleVersion", value.lifecycleVersion())
                .addValue("createdAt", timestamp(value.audit().createdAt()))
                .addValue("createdBy", createdBy.value())
                .addValue("updatedAt", timestamp(value.audit().updatedAt()))
                .addValue("updatedBy", updatedBy.value());
    }

    private MapSqlParameterSource catalogParameters(ModelCatalogEntry value) {
        PrincipalId createdBy = value.audit().createdBy().orElseThrow();
        PrincipalId updatedBy = value.audit().updatedBy().orElseThrow();
        return new MapSqlParameterSource()
                .addValue("id", value.id().value())
                .addValue("providerKey", value.providerKey().value())
                .addValue("providerDefinitionHash", value.providerDefinitionHash().value())
                .addValue("modelId", value.modelId().value())
                .addValue("catalogRevision", value.catalogRevision().value())
                .addValue("previousCatalogRevision", value.previousRevision()
                        .map(revision -> revision.value()).orElse(null))
                .addValue("modelRevision", value.modelRevision().value())
                .addValue("displayName", value.displayName())
                .addValue("contextWindowTokens", value.contextWindowTokens())
                .addValue("maximumOutputTokens", value.maximumOutputTokens())
                .addValue("capabilities", mapper.jsonb(value.capabilities().stream()
                        .map(capability -> capability.value()).sorted().toList()))
                .addValue("availableRegions", mapper.jsonb(value.availableRegions().stream()
                        .map(ModelRegion::value).sorted().toList()))
                .addValue("contentHash", value.contentHash().value())
                .addValue("status", value.status().name())
                .addValue("lifecycleVersion", value.lifecycleVersion())
                .addValue("createdAt", timestamp(value.audit().createdAt()))
                .addValue("createdBy", createdBy.value())
                .addValue("updatedAt", timestamp(value.audit().updatedAt()))
                .addValue("updatedBy", updatedBy.value());
    }

    private static MapSqlParameterSource catalogCoordinateParameters(ModelCatalogCoordinate value) {
        return new MapSqlParameterSource()
                .addValue("catalogEntryId", value.entryId().value())
                .addValue("id", value.entryId().value())
                .addValue("providerKey", value.providerKey().value())
                .addValue("modelId", value.modelId().value())
                .addValue("catalogRevision", value.catalogRevision().value());
    }

    private static MapSqlParameterSource priceParameters(ModelPriceRevision value) {
        PrincipalId createdBy = value.audit().createdBy().orElseThrow();
        return catalogCoordinateParameters(value.catalogCoordinate())
                .addValue("priceRevision", value.revision())
                .addValue("effectiveFrom", timestamp(value.effectiveFrom()))
                .addValue("inputPrice", value.tokenPrice().inputPerMillionTokens())
                .addValue("outputPrice", value.tokenPrice().outputPerMillionTokens())
                .addValue("cachedInputPrice", value.tokenPrice().cachedInputPerMillionTokens().orElse(null))
                .addValue("currencyCode", value.tokenPrice().currencyCode())
                .addValue("priceSource", value.source().value())
                .addValue("contentHash", value.contentHash().value())
                .addValue("createdAt", timestamp(value.audit().createdAt()))
                .addValue("createdBy", createdBy.value());
    }

    private void requireLifecycleUpdated(
            String aggregateType,
            String identity,
            long expected,
            int affected,
            String actualSql,
            MapSqlParameterSource parameters) {
        if (affected == 1) {
            return;
        }
        List<Long> actual = jdbc.query(actualSql, parameters, (row, ignored) -> row.getLong(1));
        if (actual.isEmpty()) {
            throw new DomainValidationException(
                    aggregateType, "does not exist for identity " + identity);
        }
        throw new DomainValidationException(
                aggregateType + ".lifecycleVersion",
                "expected " + expected + " but committed version is " + actual.get(0));
    }

    private static long previous(long current, String field) {
        if (current < 1) {
            throw new DomainValidationException(field, "must contain one lifecycle mutation");
        }
        return current - 1;
    }

    private static DomainValidationException conflict(
            String field, DataIntegrityViolationException failure) {
        DomainValidationException conflict = new DomainValidationException(
                field, "conflicts with the committed PostgreSQL registry state");
        conflict.addSuppressed(failure);
        return conflict;
    }

    private static OffsetDateTime timestamp(UtcTimestamp value) {
        return OffsetDateTime.ofInstant(value.value(), ZoneOffset.UTC);
    }

    private record PriceHead(long revision, UtcTimestamp effectiveFrom) {}
}
