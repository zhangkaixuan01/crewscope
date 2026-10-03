package io.crewscope.application.model;

import io.crewscope.domain.model.ModelAdapterKey;
import io.crewscope.domain.model.ModelCapability;
import io.crewscope.domain.model.ModelCatalogEntry;
import io.crewscope.domain.model.ModelCatalogEntryId;
import io.crewscope.domain.model.ModelCatalogRevision;
import io.crewscope.domain.model.ModelDataPolicy;
import io.crewscope.domain.model.ModelDataRetentionMode;
import io.crewscope.domain.model.ModelEndpoint;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelPriceRevision;
import io.crewscope.domain.model.ModelPriceSchedule;
import io.crewscope.domain.model.ModelPriceSource;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.model.ModelRegistryStatus;
import io.crewscope.domain.model.ModelRevision;
import io.crewscope.domain.model.ModelTokenPrice;
import io.crewscope.domain.model.ModelTrainingUsagePolicy;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Platform-owned catalog used by clean deployments and newly created Teams: the DeepSeek
 * chat provider plus the M10-I01a DashScope embedding provider, whose measured facts are
 * frozen in S01 §3.2 (dimension 1024, 0.0005 CNY per thousand tokens, non-training policy).
 */
public final class DefaultPlatformModelCatalogInitializer
    implements PlatformModelCatalogInitializer {

  static final ModelProviderKey DEEPSEEK = new ModelProviderKey("deepseek");
  static final ModelId DEEPSEEK_FLASH = new ModelId("deepseek-flash");
  static final ModelCatalogEntryId DEEPSEEK_FLASH_ENTRY_ID =
      ModelCatalogEntryId.from("0198a475-0831-7000-8000-000000000101");
  static final UtcTimestamp DEEPSEEK_PRICE_EFFECTIVE_FROM =
      UtcTimestamp.parse("2026-08-22T01:00:00Z");

  static final ModelProviderKey DASHSCOPE = new ModelProviderKey("dashscope");
  static final ModelId DASHSCOPE_TEXT_EMBEDDING_V4 = new ModelId("text-embedding-v4");
  static final ModelCatalogEntryId DASHSCOPE_EMBEDDING_ENTRY_ID =
      ModelCatalogEntryId.from("0198a475-0831-7000-8000-000000000201");
  static final UtcTimestamp DASHSCOPE_PRICE_EFFECTIVE_FROM =
      UtcTimestamp.parse("2026-10-01T00:00:00Z");

  private static final ModelRegion GLOBAL = new ModelRegion("global");
  private static final ModelRegion CHINA = new ModelRegion("cn");
  private static final ModelPriceSource DEEPSEEK_PRICE_SOURCE =
      new ModelPriceSource("https://api-docs.deepseek.com/quick_start/pricing/");
  private static final ModelPriceSource DASHSCOPE_PRICE_SOURCE =
      new ModelPriceSource("https://help.aliyun.com/zh/model-studio/embeddings");

  private final ModelProviderDefinitionRepository providers;
  private final ModelCatalogEntryRepository catalogs;
  private final ModelPriceScheduleRepository prices;

  public DefaultPlatformModelCatalogInitializer(
      ModelProviderDefinitionRepository providers,
      ModelCatalogEntryRepository catalogs,
      ModelPriceScheduleRepository prices) {
    this.providers = Objects.requireNonNull(providers, "providers");
    this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
    this.prices = Objects.requireNonNull(prices, "prices");
  }

  @Override
  public void initialize(PrincipalId actor, UtcTimestamp occurredAt) {
    PrincipalId principal = Objects.requireNonNull(actor, "actor");
    UtcTimestamp time = Objects.requireNonNull(occurredAt, "occurredAt");
    ModelProviderDefinition chatProvider = ensureProvider(principal, time);
    ModelCatalogEntry chatCatalog = ensureCatalog(chatProvider, principal, time);
    ensurePrice(chatCatalog, principal, time);
    ModelProviderDefinition embeddingProvider = ensureDashscopeProvider(principal, time);
    ModelCatalogEntry embeddingCatalog =
        ensureDashscopeCatalog(embeddingProvider, principal, time);
    ensureDashscopePrice(embeddingCatalog, principal, time);
  }

  private ModelProviderDefinition ensureDashscopeProvider(
      PrincipalId actor, UtcTimestamp occurredAt) {
    ModelProviderDefinition expected =
        ModelProviderDefinition.publish(
            DASHSCOPE,
            "DashScope",
            new ModelAdapterKey("openai-compatible"),
            new ModelEndpoint("https://dashscope.aliyuncs.com/compatible-mode/v1"),
            Set.of(CHINA),
            new ModelDataPolicy(
                ModelDataRetentionMode.PROVIDER_MANAGED,
                Optional.empty(),
                ModelTrainingUsagePolicy.PROHIBITED),
            actor,
            occurredAt);
    Optional<ModelProviderDefinition> committed = providers.findByKey(DASHSCOPE);
    if (committed.isPresent()) {
      return requireSameProvider(expected, committed.orElseThrow(), "DashScope Provider");
    }
    try {
      return providers.register(expected);
    } catch (DomainValidationException conflict) {
      return providers.findByKey(DASHSCOPE)
          .map(value -> requireSameProvider(expected, value, "DashScope Provider"))
          .orElseThrow(() -> conflict);
    }
  }

  private ModelCatalogEntry ensureDashscopeCatalog(
      ModelProviderDefinition provider, PrincipalId actor, UtcTimestamp occurredAt) {
    ModelProviderDefinition activeContract = provider.status() == ModelRegistryStatus.ACTIVE
        ? provider
        : ModelProviderDefinition.publish(
            DASHSCOPE,
            provider.displayName(),
            provider.adapterKey(),
            provider.defaultEndpoint(),
            provider.availableRegions(),
            provider.dataPolicy(),
            actor,
            occurredAt);
    ModelCatalogEntry expected =
        ModelCatalogEntry.publishInitial(
            activeContract,
            DASHSCOPE_EMBEDDING_ENTRY_ID,
            DASHSCOPE_TEXT_EMBEDDING_V4,
            new ModelRevision("text-embedding-v4"),
            "DashScope text-embedding-v4",
            // Measured per-item input cap (S01 §3.2); embeddings carry no output tokens,
            // and both token limits must stay positive in the catalog contract.
            33_000,
            1,
            Set.of(new ModelCapability("embedding")),
            Set.of(CHINA),
            actor,
            occurredAt);
    Optional<ModelCatalogEntry> committed =
        catalogs.findByEntryRevision(DASHSCOPE_EMBEDDING_ENTRY_ID, new ModelCatalogRevision(1));
    if (committed.isPresent()) {
      return requireSameInitialCatalog(expected, committed.orElseThrow(), "DashScope Embedding catalog");
    }
    if (catalogs.findLatest(DASHSCOPE, DASHSCOPE_TEXT_EMBEDDING_V4).isPresent()) {
      throw conflict("modelCatalog.contentHash", "DashScope Embedding catalog");
    }
    try {
      return catalogs.append(expected);
    } catch (DomainValidationException conflict) {
      return catalogs.findByEntryRevision(
              DASHSCOPE_EMBEDDING_ENTRY_ID, new ModelCatalogRevision(1))
          .map(value -> requireSameInitialCatalog(expected, value, "DashScope Embedding catalog"))
          .orElseThrow(() -> conflict);
    }
  }

  private void ensureDashscopePrice(
      ModelCatalogEntry catalog, PrincipalId actor, UtcTimestamp occurredAt) {
    ModelPriceRevision expected =
        ModelPriceRevision.publish(
            catalog.coordinate(),
            1,
            DASHSCOPE_PRICE_EFFECTIVE_FROM,
            new ModelTokenPrice(
                // Measured 0.0005 CNY per thousand tokens (S01 §3.2) in per-million units.
                new BigDecimal("0.5"),
                new BigDecimal("0"),
                Optional.empty(),
                "CNY"),
            DASHSCOPE_PRICE_SOURCE,
            actor,
            occurredAt);
    Optional<ModelPriceSchedule> committed = prices.findSchedule(catalog.coordinate());
    if (committed.isPresent()) {
      requireSameInitialPrice(
          expected, committed.orElseThrow().revisions().get(0), "DashScope Embedding price");
      return;
    }
    try {
      prices.append(expected);
    } catch (DomainValidationException conflict) {
      ModelPriceRevision winner =
          prices.findSchedule(catalog.coordinate())
              .map(ModelPriceSchedule::revisions)
              .filter(revisions -> !revisions.isEmpty())
              .map(revisions -> revisions.get(0))
              .orElseThrow(() -> conflict);
      requireSameInitialPrice(expected, winner, "DashScope Embedding price");
    }
  }

  private ModelProviderDefinition ensureProvider(PrincipalId actor, UtcTimestamp occurredAt) {
    ModelProviderDefinition expected = ModelProviderDefinition.publish(
        DEEPSEEK,
        "DeepSeek",
        new ModelAdapterKey("openai-compatible"),
        new ModelEndpoint("https://api.deepseek.com"),
        Set.of(GLOBAL, CHINA),
        ModelDataPolicy.noRetention(),
        actor,
        occurredAt);
    Optional<ModelProviderDefinition> committed = providers.findByKey(DEEPSEEK);
    if (committed.isPresent()) {
      return requireSameProvider(expected, committed.orElseThrow(), "DeepSeek Provider");
    }
    try {
      return providers.register(expected);
    } catch (DomainValidationException conflict) {
      return providers.findByKey(DEEPSEEK)
          .map(value -> requireSameProvider(expected, value, "DeepSeek Provider"))
          .orElseThrow(() -> conflict);
    }
  }

  private ModelCatalogEntry ensureCatalog(
      ModelProviderDefinition provider, PrincipalId actor, UtcTimestamp occurredAt) {
    // Build against an ACTIVE definition with the same immutable content. A deliberately disabled
    // Provider remains disabled in storage; initialization never changes lifecycle state.
    ModelProviderDefinition activeContract = provider.status() == ModelRegistryStatus.ACTIVE
        ? provider
        : ModelProviderDefinition.publish(
            DEEPSEEK,
            provider.displayName(),
            provider.adapterKey(),
            provider.defaultEndpoint(),
            provider.availableRegions(),
            provider.dataPolicy(),
            actor,
            occurredAt);
    ModelCatalogEntry expected = ModelCatalogEntry.publishInitial(
        activeContract,
        DEEPSEEK_FLASH_ENTRY_ID,
        DEEPSEEK_FLASH,
        new ModelRevision("DeepSeek-Flash-0731"),
        "DeepSeek Flash",
        128_000,
        8_192,
        Set.of(
            new ModelCapability("text.generation"),
            new ModelCapability("tool-calling"),
            new ModelCapability("structured-output")),
        Set.of(GLOBAL, CHINA),
        actor,
        occurredAt);
    Optional<ModelCatalogEntry> committed = catalogs.findByEntryRevision(
        DEEPSEEK_FLASH_ENTRY_ID, new ModelCatalogRevision(1));
    if (committed.isPresent()) {
      return requireSameInitialCatalog(expected, committed.orElseThrow(), "DeepSeek Flash catalog");
    }
    if (catalogs.findLatest(DEEPSEEK, DEEPSEEK_FLASH).isPresent()) {
      throw conflict("modelCatalog.contentHash", "DeepSeek Flash catalog");
    }
    try {
      return catalogs.append(expected);
    } catch (DomainValidationException conflict) {
      return catalogs.findByEntryRevision(
              DEEPSEEK_FLASH_ENTRY_ID, new ModelCatalogRevision(1))
          .map(value -> requireSameInitialCatalog(expected, value, "DeepSeek Flash catalog"))
          .orElseThrow(() -> conflict);
    }
  }

  private void ensurePrice(
      ModelCatalogEntry catalog,
      PrincipalId actor,
      UtcTimestamp occurredAt) {
    ModelPriceRevision expected = ModelPriceRevision.publish(
            catalog.coordinate(),
            1,
            DEEPSEEK_PRICE_EFFECTIVE_FROM,
            new ModelTokenPrice(
                new BigDecimal("0.44"),
                new BigDecimal("1.32"),
                Optional.of(new BigDecimal("0.014")),
                "USD"),
            DEEPSEEK_PRICE_SOURCE,
            actor,
            occurredAt);
    Optional<ModelPriceSchedule> committed = prices.findSchedule(catalog.coordinate());
    if (committed.isPresent()) {
      requireSameInitialPrice(
          expected, committed.orElseThrow().revisions().get(0), "DeepSeek Flash price");
      return;
    }
    try {
      prices.append(expected);
    } catch (DomainValidationException conflict) {
      ModelPriceRevision winner = prices.findSchedule(catalog.coordinate())
          .map(ModelPriceSchedule::revisions)
          .filter(revisions -> !revisions.isEmpty())
          .map(revisions -> revisions.get(0))
          .orElseThrow(() -> conflict);
      requireSameInitialPrice(expected, winner, "DeepSeek Flash price");
    }
  }

  private static ModelProviderDefinition requireSameProvider(
      ModelProviderDefinition expected,
      ModelProviderDefinition committed,
      String fact) {
    if (!committed.contentHash().equals(expected.contentHash())) {
      throw conflict("modelProvider.contentHash", fact);
    }
    return committed;
  }

  private static ModelCatalogEntry requireSameInitialCatalog(
      ModelCatalogEntry expected, ModelCatalogEntry committed, String fact) {
    if (!committed.id().equals(expected.id())
        || committed.catalogRevision().value() != 1
        || !committed.contentHash().equals(expected.contentHash())) {
      throw conflict("modelCatalog.contentHash", fact);
    }
    return committed;
  }

  private static void requireSameInitialPrice(
      ModelPriceRevision expected, ModelPriceRevision committed, String fact) {
    if (!committed.contentHash().equals(expected.contentHash())) {
      throw conflict("modelPrice.contentHash", fact);
    }
  }

  private static DomainValidationException conflict(String field, String fact) {
    return new DomainValidationException(
        field, "the committed platform-owned " + fact + " differs from the built-in definition");
  }
}
