package io.crewscope.application.model;

import io.crewscope.domain.model.ModelAdapterKey;
import io.crewscope.domain.model.ModelCapability;
import io.crewscope.domain.model.ModelCatalogEntry;
import io.crewscope.domain.model.ModelCatalogEntryId;
import io.crewscope.domain.model.ModelCatalogRevision;
import io.crewscope.domain.model.ModelDataPolicy;
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
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Platform-owned DeepSeek catalog used by clean deployments and newly created Teams. */
public final class DefaultPlatformModelCatalogInitializer
    implements PlatformModelCatalogInitializer {

  static final ModelProviderKey DEEPSEEK = new ModelProviderKey("deepseek");
  static final ModelId DEEPSEEK_V4_FLASH = new ModelId("deepseek-v4-flash");
  static final ModelCatalogEntryId DEEPSEEK_V4_FLASH_ENTRY_ID =
      ModelCatalogEntryId.from("0198a475-0831-7000-8000-000000000101");
  static final UtcTimestamp DEEPSEEK_PRICE_EFFECTIVE_FROM =
      UtcTimestamp.parse("2026-08-22T01:00:00Z");

  private static final ModelRegion GLOBAL = new ModelRegion("global");
  private static final ModelRegion CHINA = new ModelRegion("cn");
  private static final ModelPriceSource DEEPSEEK_PRICE_SOURCE =
      new ModelPriceSource("https://api-docs.deepseek.com/quick_start/pricing/");

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
    ModelProviderDefinition provider = ensureProvider(principal, time);
    ModelCatalogEntry catalog = ensureCatalog(provider, principal, time);
    ensurePrice(catalog, principal, time);
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
      return requireSameProvider(expected, committed.orElseThrow());
    }
    try {
      return providers.register(expected);
    } catch (DomainValidationException conflict) {
      return providers.findByKey(DEEPSEEK)
          .map(value -> requireSameProvider(expected, value))
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
        DEEPSEEK_V4_FLASH_ENTRY_ID,
        DEEPSEEK_V4_FLASH,
        new ModelRevision("DeepSeek-V4-Flash-0731"),
        "DeepSeek V4 Flash",
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
        DEEPSEEK_V4_FLASH_ENTRY_ID, new ModelCatalogRevision(1));
    if (committed.isPresent()) {
      return requireSameInitialCatalog(expected, committed.orElseThrow());
    }
    if (catalogs.findLatest(DEEPSEEK, DEEPSEEK_V4_FLASH).isPresent()) {
      throw conflict("modelCatalog.contentHash", "DeepSeek V4 Flash catalog");
    }
    try {
      return catalogs.append(expected);
    } catch (DomainValidationException conflict) {
      return catalogs.findByEntryRevision(
              DEEPSEEK_V4_FLASH_ENTRY_ID, new ModelCatalogRevision(1))
          .map(value -> requireSameInitialCatalog(expected, value))
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
      requireSameInitialPrice(expected, committed.orElseThrow().revisions().get(0));
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
      requireSameInitialPrice(expected, winner);
    }
  }

  private static ModelProviderDefinition requireSameProvider(
      ModelProviderDefinition expected, ModelProviderDefinition committed) {
    if (!committed.contentHash().equals(expected.contentHash())) {
      throw conflict("modelProvider.contentHash", "DeepSeek Provider");
    }
    return committed;
  }

  private static ModelCatalogEntry requireSameInitialCatalog(
      ModelCatalogEntry expected, ModelCatalogEntry committed) {
    if (!committed.id().equals(expected.id())
        || committed.catalogRevision().value() != 1
        || !committed.contentHash().equals(expected.contentHash())) {
      throw conflict("modelCatalog.contentHash", "DeepSeek V4 Flash catalog");
    }
    return committed;
  }

  private static void requireSameInitialPrice(
      ModelPriceRevision expected, ModelPriceRevision committed) {
    if (!committed.contentHash().equals(expected.contentHash())) {
      throw conflict("modelPrice.contentHash", "DeepSeek V4 Flash price");
    }
  }

  private static DomainValidationException conflict(String field, String fact) {
    return new DomainValidationException(
        field, "the committed platform-owned " + fact + " differs from the built-in definition");
  }
}
