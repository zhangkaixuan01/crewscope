package io.crewscope.application.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.model.ModelAdapterKey;
import io.crewscope.domain.model.ModelCatalogCoordinate;
import io.crewscope.domain.model.ModelCatalogEntry;
import io.crewscope.domain.model.ModelCatalogEntryId;
import io.crewscope.domain.model.ModelCatalogRevision;
import io.crewscope.domain.model.ModelDataPolicy;
import io.crewscope.domain.model.ModelDataRetentionMode;
import io.crewscope.domain.model.ModelEndpoint;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelPriceRevision;
import io.crewscope.domain.model.ModelPriceSchedule;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.model.ModelRegistryHash;
import io.crewscope.domain.model.ModelRegistryStatus;
import io.crewscope.domain.model.ModelTrainingUsagePolicy;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultPlatformModelCatalogInitializerTest {

  private static final PrincipalId ACTOR = PrincipalId.generate();
  private static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-08-31T08:00:00Z");

  @Test
  void initializesTheCompleteNonSecretCatalogAndReplaysWithoutWrites() {
    Registry registry = new Registry();
    DefaultPlatformModelCatalogInitializer initializer =
        new DefaultPlatformModelCatalogInitializer(registry, registry, registry);

    initializer.initialize(ACTOR, CREATED_AT);
    initializer.initialize(ACTOR, UtcTimestamp.parse("2026-08-31T08:01:00Z"));

    assertEquals(2, registry.providerWrites, "one provider write per seeded provider");
    assertEquals(2, registry.catalogWrites, "one catalog write per seeded entry");
    assertEquals(2, registry.priceWrites, "one price write per seeded entry");

    ModelProviderDefinition deepseek =
        registry.providers.get(DefaultPlatformModelCatalogInitializer.DEEPSEEK);
    assertEquals("openai-compatible", deepseek.adapterKey().toString());
    assertEquals("https://api.deepseek.com", deepseek.defaultEndpoint().toString());

    ModelCatalogEntry flash =
        registry.catalogByEntry(DefaultPlatformModelCatalogInitializer.DEEPSEEK_FLASH_ENTRY_ID);
    assertEquals("deepseek-flash", flash.modelId().toString());
    assertTrue(flash.capabilities().stream()
        .map(Object::toString)
        .toList()
        .containsAll(List.of("tool-calling", "structured-output")));

    ModelPriceRevision flashPrice =
        registry.schedules.get(flash.coordinate()).revisions().get(0);
    assertEquals("0.44", flashPrice.tokenPrice().inputPerMillionTokens().toPlainString());
    assertEquals("1.32", flashPrice.tokenPrice().outputPerMillionTokens().toPlainString());
    assertEquals(
        "0.014",
        flashPrice.tokenPrice().cachedInputPerMillionTokens().orElseThrow().toPlainString());
  }

  @Test
  void seedsTheMeasuredDashscopeEmbeddingFactsExactlyOnce() {
    Registry registry = new Registry();
    DefaultPlatformModelCatalogInitializer initializer =
        new DefaultPlatformModelCatalogInitializer(registry, registry, registry);

    initializer.initialize(ACTOR, CREATED_AT);

    ModelProviderDefinition dashscope =
        registry.providers.get(DefaultPlatformModelCatalogInitializer.DASHSCOPE);
    assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1",
        dashscope.defaultEndpoint().toString());
    assertEquals(
        new ModelDataPolicy(
            ModelDataRetentionMode.PROVIDER_MANAGED,
            Optional.empty(),
            ModelTrainingUsagePolicy.PROHIBITED),
        dashscope.dataPolicy(),
        "the frozen outbound data policy must match the S01 measured facts");

    ModelCatalogEntry embedding =
        registry.catalogByEntry(DefaultPlatformModelCatalogInitializer.DASHSCOPE_EMBEDDING_ENTRY_ID);
    assertEquals("text-embedding-v4", embedding.modelId().toString());
    assertEquals(33_000, embedding.contextWindowTokens());
    assertEquals(1, embedding.maximumOutputTokens());
    assertTrue(embedding.capabilities().stream()
        .map(Object::toString)
        .toList()
        .contains("embedding"));

    ModelPriceRevision price = registry.schedules.get(embedding.coordinate()).revisions().get(0);
    assertEquals("0.5", price.tokenPrice().inputPerMillionTokens().toPlainString());
    assertEquals("0", price.tokenPrice().outputPerMillionTokens().toPlainString());
    assertEquals("CNY", price.tokenPrice().currencyCode());
    assertEquals(
        DefaultPlatformModelCatalogInitializer.DASHSCOPE_PRICE_EFFECTIVE_FROM,
        price.effectiveFrom());
  }

  @Test
  void adoptsDriftedPlatformOwnedProviderContentInsteadOfFailing() {
    Registry registry = new Registry();
    registry.providers.put(
        DefaultPlatformModelCatalogInitializer.DEEPSEEK,
        ModelProviderDefinition.publish(
            DefaultPlatformModelCatalogInitializer.DEEPSEEK,
            "Conflicting DeepSeek",
            new ModelAdapterKey("openai-compatible"),
            new ModelEndpoint("https://gateway.example.com"),
            Set.of(new ModelRegion("global")),
            ModelDataPolicy.noRetention(),
            ACTOR,
            CREATED_AT));
    DefaultPlatformModelCatalogInitializer initializer =
        new DefaultPlatformModelCatalogInitializer(registry, registry, registry);

    initializer.initialize(ACTOR, UtcTimestamp.parse("2026-08-31T08:05:00Z"));

    ModelProviderDefinition adopted =
        registry.providers.get(DefaultPlatformModelCatalogInitializer.DEEPSEEK);
    assertEquals("DeepSeek", adopted.displayName(), "content converges to the built-in definition");
    assertEquals(
        "https://api.deepseek.com", adopted.defaultEndpoint().toString());
    assertEquals(1, registry.adoptions, "drift is resolved by exactly one adoption");
    assertEquals(
        2,
        registry.providerWrites,
        "the drifted DeepSeek provider adopts and the untouched DashScope provider registers");
    assertEquals(2, registry.catalogWrites, "the deepseek-flash entry still seeds after adoption");
    assertEquals(2, registry.priceWrites);
  }

  @Test
  void adoptionPreservesDeliberatelyDisabledLifecycleState() {
    Registry registry = new Registry();
    DefaultPlatformModelCatalogInitializer initializer =
        new DefaultPlatformModelCatalogInitializer(registry, registry, registry);
    initializer.initialize(ACTOR, CREATED_AT);
    UtcTimestamp disabledAt = UtcTimestamp.parse("2026-08-31T08:06:00Z");
    registry.providers.put(
        DefaultPlatformModelCatalogInitializer.DEEPSEEK,
        registry.providers.get(DefaultPlatformModelCatalogInitializer.DEEPSEEK).disable(ACTOR, disabledAt));
    // Drift the committed content so the next initialization must go through adoption.
    registry.providers.put(
        DefaultPlatformModelCatalogInitializer.DEEPSEEK,
        registry.providers.get(DefaultPlatformModelCatalogInitializer.DEEPSEEK).republishContent(
            "DeepSeek Legacy",
            new ModelAdapterKey("openai-compatible"),
            new ModelEndpoint("https://legacy.example.com"),
            Set.of(new ModelRegion("global")),
            ModelDataPolicy.noRetention(),
            ACTOR,
            UtcTimestamp.parse("2026-08-31T08:07:00Z")));

    initializer.initialize(ACTOR, UtcTimestamp.parse("2026-08-31T08:08:00Z"));

    ModelProviderDefinition adopted =
        registry.providers.get(DefaultPlatformModelCatalogInitializer.DEEPSEEK);
    assertEquals(
        ModelRegistryStatus.DISABLED, adopted.status(), "adoption never changes lifecycle state");
    assertEquals("DeepSeek", adopted.displayName(), "content still converges to the built-in definition");
    assertEquals(1, registry.adoptions);
  }

  @Test
  void adoptsDriftedDashscopeDefinitionAfterTheDeepseekSegmentSucceeded() {
    Registry registry = new Registry();
    registry.providers.put(
        DefaultPlatformModelCatalogInitializer.DASHSCOPE,
        ModelProviderDefinition.publish(
            DefaultPlatformModelCatalogInitializer.DASHSCOPE,
            "Conflicting DashScope",
            new ModelAdapterKey("openai-compatible"),
            new ModelEndpoint("https://gateway.example.com"),
            Set.of(new ModelRegion("cn")),
            ModelDataPolicy.noRetention(),
            ACTOR,
            CREATED_AT));
    DefaultPlatformModelCatalogInitializer initializer =
        new DefaultPlatformModelCatalogInitializer(registry, registry, registry);

    initializer.initialize(ACTOR, UtcTimestamp.parse("2026-08-31T08:09:00Z"));

    ModelProviderDefinition adopted =
        registry.providers.get(DefaultPlatformModelCatalogInitializer.DASHSCOPE);
    assertEquals("DashScope", adopted.displayName());
    assertEquals(
        "https://dashscope.aliyuncs.com/compatible-mode/v1",
        adopted.defaultEndpoint().toString());
    assertEquals(1, registry.adoptions, "only the drifted DashScope provider needs adoption");
    assertEquals(2, registry.providerWrites, "one register plus one adoption");
    assertEquals(2, registry.catalogWrites);
    assertEquals(2, registry.priceWrites);
  }

  @Test
  void preservesDisabledLifecycleFactsDuringAStartupReplay() {
    Registry registry = new Registry();
    DefaultPlatformModelCatalogInitializer initializer =
        new DefaultPlatformModelCatalogInitializer(registry, registry, registry);
    initializer.initialize(ACTOR, CREATED_AT);
    UtcTimestamp disabledAt = UtcTimestamp.parse("2026-08-31T08:02:00Z");
    registry.providers.put(
        DefaultPlatformModelCatalogInitializer.DEEPSEEK,
        registry.providers.get(DefaultPlatformModelCatalogInitializer.DEEPSEEK)
            .disable(ACTOR, disabledAt));
    registry.catalogs.put(
        DefaultPlatformModelCatalogInitializer.DEEPSEEK_FLASH_ENTRY_ID,
        registry.catalogByEntry(DefaultPlatformModelCatalogInitializer.DEEPSEEK_FLASH_ENTRY_ID)
            .disable(ACTOR, disabledAt));

    initializer.initialize(ACTOR, UtcTimestamp.parse("2026-08-31T08:03:00Z"));

    assertEquals(
        ModelRegistryStatus.DISABLED,
        registry.providers.get(DefaultPlatformModelCatalogInitializer.DEEPSEEK).status());
    assertEquals(
        ModelRegistryStatus.DISABLED,
        registry.catalogByEntry(DefaultPlatformModelCatalogInitializer.DEEPSEEK_FLASH_ENTRY_ID)
            .status());
    assertEquals(
        ModelRegistryStatus.ACTIVE,
        registry.providers.get(DefaultPlatformModelCatalogInitializer.DASHSCOPE).status(),
        "a disabled neighbor never disables the embedding provider");
    assertEquals(2, registry.providerWrites);
    assertEquals(2, registry.catalogWrites);
    assertEquals(2, registry.priceWrites);
  }

  @Test
  void dashscopePriceBecomesEffectiveOnOctoberFirstOnly() {
    Registry registry = new Registry();
    DefaultPlatformModelCatalogInitializer initializer =
        new DefaultPlatformModelCatalogInitializer(registry, registry, registry);
    initializer.initialize(ACTOR, CREATED_AT);

    ModelCatalogEntry embedding =
        registry.catalogByEntry(DefaultPlatformModelCatalogInitializer.DASHSCOPE_EMBEDDING_ENTRY_ID);
    ModelCatalogCoordinate coordinate = embedding.coordinate();

    assertTrue(
        registry.findEffectivePrice(coordinate, UtcTimestamp.parse("2026-09-30T23:59:59Z"))
            .isEmpty(),
        "the price must not apply before the frozen effective moment");
    assertTrue(
        registry.findEffectivePrice(coordinate, UtcTimestamp.parse("2026-10-01T00:00:00Z"))
            .isPresent());
  }

  private static final class Registry
      implements ModelProviderDefinitionRepository,
          ModelCatalogEntryRepository,
          ModelPriceScheduleRepository {

    private final Map<ModelProviderKey, ModelProviderDefinition> providers =
        new LinkedHashMap<>();
    private final Map<ModelCatalogEntryId, ModelCatalogEntry> catalogs = new LinkedHashMap<>();
    private final Map<ModelCatalogCoordinate, ModelPriceSchedule> schedules =
        new LinkedHashMap<>();
    private int providerWrites;
    private int catalogWrites;
    private int priceWrites;
    private int adoptions;

    ModelCatalogEntry catalogByEntry(ModelCatalogEntryId entryId) {
      return catalogs.get(entryId);
    }

    @Override
    public ModelProviderDefinition register(ModelProviderDefinition definition) {
      providerWrites++;
      providers.put(definition.providerKey(), definition);
      return definition;
    }

    @Override
    public ModelProviderDefinition adoptPlatformContent(
        ModelRegistryHash previousContentHash, ModelProviderDefinition replacement) {
      adoptions++;
      providerWrites++;
      ModelProviderDefinition committed = providers.get(replacement.providerKey());
      if (committed == null || !committed.contentHash().equals(previousContentHash)) {
        throw new DomainValidationException(
            "modelProvider.contentHash", "changed concurrently during platform content adoption");
      }
      providers.put(replacement.providerKey(), replacement);
      return replacement;
    }

    @Override
    public ModelProviderDefinition updateLifecycle(ModelProviderDefinition definition) {
      providers.put(definition.providerKey(), definition);
      return definition;
    }

    @Override
    public Optional<ModelProviderDefinition> findByKey(ModelProviderKey providerKey) {
      return Optional.ofNullable(providers.get(providerKey));
    }

    @Override
    public List<ModelProviderDefinition> findPage(int offset, int limit) {
      return List.copyOf(providers.values());
    }

    @Override
    public ModelCatalogEntry append(ModelCatalogEntry entry) {
      catalogWrites++;
      catalogs.put(entry.id(), entry);
      return entry;
    }

    @Override
    public ModelCatalogEntry updateLifecycle(ModelCatalogEntry entry) {
      catalogs.put(entry.id(), entry);
      return entry;
    }

    @Override
    public Optional<ModelCatalogEntry> findByCoordinate(ModelCatalogCoordinate coordinate) {
      return catalogs.values().stream()
          .filter(value -> value.coordinate().equals(coordinate))
          .findFirst();
    }

    @Override
    public Optional<ModelCatalogEntry> findByEntryRevision(
        ModelCatalogEntryId entryId, ModelCatalogRevision revision) {
      return Optional.ofNullable(catalogs.get(entryId))
          .filter(value -> value.catalogRevision().equals(revision));
    }

    @Override
    public Optional<ModelCatalogEntry> findLatest(
        ModelProviderKey providerKey, ModelId modelId) {
      return catalogs.values().stream()
          .filter(
              value -> value.providerKey().equals(providerKey)
                  && value.modelId().equals(modelId))
          .max(Comparator.comparing(ModelCatalogEntry::catalogRevision));
    }

    @Override
    public List<ModelCatalogEntry> findPage(
        ModelProviderKey providerKey, int offset, int limit) {
      return catalogs.values().stream()
          .filter(value -> value.providerKey().equals(providerKey))
          .sorted(Comparator.comparing(ModelCatalogEntry::modelId)
              .thenComparing(ModelCatalogEntry::catalogRevision, Comparator.reverseOrder()))
          .toList();
    }

    @Override
    public ModelPriceRevision append(ModelPriceRevision priceRevision) {
      priceWrites++;
      schedules.put(
          priceRevision.catalogCoordinate(),
          ModelPriceSchedule.reconstitute(
              priceRevision.catalogCoordinate(), List.of(priceRevision)));
      return priceRevision;
    }

    @Override
    public Optional<ModelPriceSchedule> findSchedule(ModelCatalogCoordinate coordinate) {
      return Optional.ofNullable(schedules.get(coordinate));
    }

    @Override
    public Optional<ModelPriceRevision> findEffectivePrice(
        ModelCatalogCoordinate coordinate, UtcTimestamp effectiveAt) {
      return findSchedule(coordinate).flatMap(value -> value.priceAt(effectiveAt));
    }
  }
}
