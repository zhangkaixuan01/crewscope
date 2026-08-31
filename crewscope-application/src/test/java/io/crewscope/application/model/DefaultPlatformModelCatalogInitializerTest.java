package io.crewscope.application.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.model.ModelAdapterKey;
import io.crewscope.domain.model.ModelCatalogCoordinate;
import io.crewscope.domain.model.ModelCatalogEntry;
import io.crewscope.domain.model.ModelCatalogEntryId;
import io.crewscope.domain.model.ModelCatalogRevision;
import io.crewscope.domain.model.ModelDataPolicy;
import io.crewscope.domain.model.ModelEndpoint;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelPriceRevision;
import io.crewscope.domain.model.ModelPriceSchedule;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.model.ModelRegistryStatus;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.List;
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

    assertEquals(1, registry.providerWrites);
    assertEquals(1, registry.catalogWrites);
    assertEquals(1, registry.priceWrites);
    assertEquals("openai-compatible", registry.provider.adapterKey().toString());
    assertEquals("https://api.deepseek.com", registry.provider.defaultEndpoint().toString());
    assertEquals("deepseek-v4-flash", registry.catalog.modelId().toString());
    assertTrue(registry.catalog.capabilities().stream()
        .map(Object::toString)
        .toList()
        .containsAll(List.of("tool-calling", "structured-output")));
    ModelPriceRevision price = registry.schedule.revisions().get(0);
    assertEquals("0.44", price.tokenPrice().inputPerMillionTokens().toPlainString());
    assertEquals("1.32", price.tokenPrice().outputPerMillionTokens().toPlainString());
    assertEquals(
        "0.014",
        price.tokenPrice().cachedInputPerMillionTokens().orElseThrow().toPlainString());
  }

  @Test
  void rejectsAConflictingPlatformOwnedProviderWithoutOverwritingIt() {
    Registry registry = new Registry();
    registry.provider = ModelProviderDefinition.publish(
        DefaultPlatformModelCatalogInitializer.DEEPSEEK,
        "Conflicting DeepSeek",
        new ModelAdapterKey("openai-compatible"),
        new ModelEndpoint("https://gateway.example.com"),
        Set.of(new ModelRegion("global")),
        ModelDataPolicy.noRetention(),
        ACTOR,
        CREATED_AT);
    DefaultPlatformModelCatalogInitializer initializer =
        new DefaultPlatformModelCatalogInitializer(registry, registry, registry);

    assertThrows(
        DomainValidationException.class,
        () -> initializer.initialize(ACTOR, CREATED_AT));
    assertEquals(0, registry.providerWrites);
    assertEquals(0, registry.catalogWrites);
    assertEquals(0, registry.priceWrites);
  }

  @Test
  void preservesDisabledLifecycleFactsDuringAStartupReplay() {
    Registry registry = new Registry();
    DefaultPlatformModelCatalogInitializer initializer =
        new DefaultPlatformModelCatalogInitializer(registry, registry, registry);
    initializer.initialize(ACTOR, CREATED_AT);
    UtcTimestamp disabledAt = UtcTimestamp.parse("2026-08-31T08:02:00Z");
    registry.provider = registry.provider.disable(ACTOR, disabledAt);
    registry.catalog = registry.catalog.disable(ACTOR, disabledAt);

    initializer.initialize(ACTOR, UtcTimestamp.parse("2026-08-31T08:03:00Z"));

    assertEquals(ModelRegistryStatus.DISABLED, registry.provider.status());
    assertEquals(ModelRegistryStatus.DISABLED, registry.catalog.status());
    assertEquals(1, registry.providerWrites);
    assertEquals(1, registry.catalogWrites);
    assertEquals(1, registry.priceWrites);
  }

  private static final class Registry
      implements ModelProviderDefinitionRepository,
          ModelCatalogEntryRepository,
          ModelPriceScheduleRepository {

    private ModelProviderDefinition provider;
    private ModelCatalogEntry catalog;
    private ModelPriceSchedule schedule;
    private int providerWrites;
    private int catalogWrites;
    private int priceWrites;

    @Override
    public ModelProviderDefinition register(ModelProviderDefinition definition) {
      providerWrites++;
      provider = definition;
      return definition;
    }

    @Override
    public ModelProviderDefinition updateLifecycle(ModelProviderDefinition definition) {
      provider = definition;
      return definition;
    }

    @Override
    public Optional<ModelProviderDefinition> findByKey(ModelProviderKey providerKey) {
      return Optional.ofNullable(provider)
          .filter(value -> value.providerKey().equals(providerKey));
    }

    @Override
    public List<ModelProviderDefinition> findPage(int offset, int limit) {
      return Optional.ofNullable(provider).map(List::of).orElseGet(List::of);
    }

    @Override
    public ModelCatalogEntry append(ModelCatalogEntry entry) {
      catalogWrites++;
      catalog = entry;
      return entry;
    }

    @Override
    public ModelCatalogEntry updateLifecycle(ModelCatalogEntry entry) {
      catalog = entry;
      return entry;
    }

    @Override
    public Optional<ModelCatalogEntry> findByCoordinate(ModelCatalogCoordinate coordinate) {
      return Optional.ofNullable(catalog)
          .filter(value -> value.coordinate().equals(coordinate));
    }

    @Override
    public Optional<ModelCatalogEntry> findByEntryRevision(
        ModelCatalogEntryId entryId, ModelCatalogRevision revision) {
      return Optional.ofNullable(catalog)
          .filter(value -> value.id().equals(entryId) && value.catalogRevision().equals(revision));
    }

    @Override
    public Optional<ModelCatalogEntry> findLatest(
        ModelProviderKey providerKey, ModelId modelId) {
      return Optional.ofNullable(catalog)
          .filter(value -> value.providerKey().equals(providerKey) && value.modelId().equals(modelId));
    }

    @Override
    public List<ModelCatalogEntry> findPage(
        ModelProviderKey providerKey, int offset, int limit) {
      return findLatest(providerKey, DefaultPlatformModelCatalogInitializer.DEEPSEEK_V4_FLASH)
          .map(List::of)
          .orElseGet(List::of);
    }

    @Override
    public ModelPriceRevision append(ModelPriceRevision priceRevision) {
      priceWrites++;
      schedule = ModelPriceSchedule.reconstitute(
          priceRevision.catalogCoordinate(), List.of(priceRevision));
      return priceRevision;
    }

    @Override
    public Optional<ModelPriceSchedule> findSchedule(ModelCatalogCoordinate coordinate) {
      return Optional.ofNullable(schedule)
          .filter(value -> value.catalogCoordinate().equals(coordinate));
    }

    @Override
    public Optional<ModelPriceRevision> findEffectivePrice(
        ModelCatalogCoordinate coordinate, UtcTimestamp effectiveAt) {
      return findSchedule(coordinate).flatMap(value -> value.priceAt(effectiveAt));
    }
  }
}
