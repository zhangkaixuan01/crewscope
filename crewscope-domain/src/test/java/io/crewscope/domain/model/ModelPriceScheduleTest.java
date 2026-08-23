package io.crewscope.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelPriceScheduleTest {

    private static final PrincipalId ACTOR = PrincipalId.generate();
    private static final UtcTimestamp CREATED_AT =
            UtcTimestamp.parse("2026-08-23T01:00:00Z");
    private static final UtcTimestamp SEPTEMBER =
            UtcTimestamp.parse("2026-09-01T00:00:00Z");
    private static final UtcTimestamp OCTOBER =
            UtcTimestamp.parse("2026-10-01T00:00:00Z");

    @Test
    void derivesNonOverlappingHalfOpenSlicesAndResolvesPriceAtBoundary() {
        Fixture fixture = Fixture.create();
        ModelPriceSchedule september = ModelPriceSchedule.start(
                fixture.provider,
                fixture.catalog,
                SEPTEMBER,
                price("2.00", "8.00", "0.50"),
                new ModelPriceSource("https://api-docs.deepseek.com/pricing/2026-09"),
                ACTOR,
                CREATED_AT);
        ModelPriceSchedule october = september.append(
                fixture.provider,
                fixture.catalog,
                OCTOBER,
                price("1.50", "6.00", "0.40"),
                new ModelPriceSource("manual:pricing-review-2026-10"),
                ACTOR,
                CREATED_AT);

        List<ModelPriceTimeSlice> slices = october.timeSlices();
        assertEquals(2, slices.size());
        assertEquals(Optional.of(OCTOBER), slices.get(0).effectiveUntil());
        assertTrue(slices.get(0).contains(
                UtcTimestamp.parse("2026-09-30T23:59:59Z")));
        assertTrue(slices.get(1).contains(OCTOBER));
        assertEquals(
                new BigDecimal("2"),
                october.priceAt(UtcTimestamp.parse("2026-09-15T00:00:00Z"))
                        .orElseThrow()
                        .tokenPrice()
                        .inputPerMillionTokens());
        assertEquals(
                new BigDecimal("1.5"),
                october.priceAt(OCTOBER)
                        .orElseThrow()
                        .tokenPrice()
                        .inputPerMillionTokens());
        assertTrue(october.priceAt(
                        UtcTimestamp.parse("2026-08-31T23:59:59Z"))
                .isEmpty());
    }

    @Test
    void rejectsOverlappingOrCrossCatalogPriceAppendAndDisabledRegistryFacts() {
        Fixture fixture = Fixture.create();
        ModelPriceSchedule schedule = ModelPriceSchedule.start(
                fixture.provider,
                fixture.catalog,
                SEPTEMBER,
                price("2", "8", "0.5"),
                new ModelPriceSource("initial"),
                ACTOR,
                CREATED_AT);

        assertThrows(
                DomainValidationException.class,
                () -> schedule.append(
                        fixture.provider,
                        fixture.catalog,
                        SEPTEMBER,
                        price("1", "4", "0.25"),
                        new ModelPriceSource("overlap"),
                        ACTOR,
                        CREATED_AT));

        ModelCatalogEntry otherCatalog = Fixture.catalog(fixture.provider);
        assertThrows(
                DomainValidationException.class,
                () -> schedule.append(
                        fixture.provider,
                        otherCatalog,
                        OCTOBER,
                        price("1", "4", "0.25"),
                        new ModelPriceSource("other-catalog"),
                        ACTOR,
                        CREATED_AT));

        ModelProviderDefinition disabledProvider = fixture.provider.disable(
                ACTOR, UtcTimestamp.parse("2026-08-23T01:01:00Z"));
        assertThrows(
                DomainValidationException.class,
                () -> schedule.append(
                        disabledProvider,
                        fixture.catalog,
                        OCTOBER,
                        price("1", "4", "0.25"),
                        new ModelPriceSource("disabled-provider"),
                        ACTOR,
                        CREATED_AT));

        ModelCatalogEntry disabledCatalog = fixture.catalog.disable(
                ACTOR, UtcTimestamp.parse("2026-08-23T01:01:00Z"));
        assertThrows(
                DomainValidationException.class,
                () -> schedule.append(
                        fixture.provider,
                        disabledCatalog,
                        OCTOBER,
                        price("1", "4", "0.25"),
                        new ModelPriceSource("disabled-catalog"),
                        ACTOR,
                        CREATED_AT));
    }

    @Test
    void appendPreservesHistoricalPriceRevisionAndOldScheduleHash() {
        Fixture fixture = Fixture.create();
        ModelPriceSchedule original = ModelPriceSchedule.start(
                fixture.provider,
                fixture.catalog,
                SEPTEMBER,
                price("2.0000", "8.000", "0.500"),
                new ModelPriceSource("initial"),
                ACTOR,
                CREATED_AT);
        ModelPriceRevision historical = original.revisions().get(0);

        ModelPriceSchedule appended = original.append(
                fixture.provider,
                fixture.catalog,
                OCTOBER,
                price("1.5", "6", "0.4"),
                new ModelPriceSource("next"),
                ACTOR,
                CREATED_AT);

        assertEquals(1, original.revisions().size());
        assertEquals(historical.contentHash(), appended.revisions().get(0).contentHash());
        assertEquals(
                historical.contentHash(),
                ModelPriceRevision.reconstitute(
                                historical.catalogCoordinate(),
                                historical.revision(),
                                historical.effectiveFrom(),
                                historical.tokenPrice(),
                                historical.source(),
                                historical.contentHash(),
                                historical.audit())
                        .contentHash());
        assertEquals(Optional.of(ACTOR), historical.audit().createdBy());
        assertEquals(new BigDecimal("2"), historical.tokenPrice().inputPerMillionTokens());
        assertNotEquals(original.scheduleHash(), appended.scheduleHash());
        assertEquals(1, historical.revision());
        assertEquals(2, appended.revisions().get(1).revision());
    }

    @Test
    void reconstitutionRejectsRevisionGapsTimeOverlapAndForgedScheduleHash() {
        Fixture fixture = Fixture.create();
        ModelCatalogCoordinate coordinate = fixture.catalog.coordinate();
        ModelPriceRevision first = ModelPriceRevision.publish(
                coordinate,
                1,
                SEPTEMBER,
                price("2", "8", "0.5"),
                new ModelPriceSource("initial"),
                ACTOR,
                CREATED_AT);
        ModelPriceRevision revisionGap = ModelPriceRevision.publish(
                coordinate,
                3,
                OCTOBER,
                price("1", "4", "0.25"),
                new ModelPriceSource("gap"),
                ACTOR,
                CREATED_AT);
        ModelPriceRevision overlap = ModelPriceRevision.publish(
                coordinate,
                2,
                SEPTEMBER,
                price("1", "4", "0.25"),
                new ModelPriceSource("overlap"),
                ACTOR,
                CREATED_AT);

        assertThrows(
                DomainValidationException.class,
                () -> ModelPriceSchedule.reconstitute(
                        coordinate,
                        List.of(first, revisionGap),
                        ModelRegistryHash.sha256("unused")));
        assertThrows(
                DomainValidationException.class,
                () -> ModelPriceSchedule.reconstitute(
                        coordinate,
                        List.of(first, overlap),
                        ModelRegistryHash.sha256("unused")));

        ModelPriceSchedule valid = ModelPriceSchedule.start(
                fixture.provider,
                fixture.catalog,
                SEPTEMBER,
                price("2", "8", "0.5"),
                new ModelPriceSource("initial"),
                ACTOR,
                CREATED_AT);
        assertThrows(
                DomainValidationException.class,
                () -> ModelPriceSchedule.reconstitute(
                        coordinate,
                        valid.revisions(),
                        ModelRegistryHash.sha256("forged")));
    }

    private static ModelTokenPrice price(String input, String output, String cachedInput) {
        return new ModelTokenPrice(
                new BigDecimal(input),
                new BigDecimal(output),
                Optional.of(new BigDecimal(cachedInput)),
                "USD");
    }

    private record Fixture(ModelProviderDefinition provider, ModelCatalogEntry catalog) {

        private static Fixture create() {
            ModelProviderDefinition provider = ModelProviderDefinition.publish(
                    new ModelProviderKey("deepseek"),
                    "DeepSeek",
                    new ModelAdapterKey("openai-compatible"),
                    new ModelEndpoint("https://api.deepseek.com/v1"),
                    Set.of(new ModelRegion("global"), new ModelRegion("cn")),
                    ModelDataPolicy.noRetention(),
                    ACTOR,
                    CREATED_AT);
            return new Fixture(provider, catalog(provider));
        }

        private static ModelCatalogEntry catalog(ModelProviderDefinition provider) {
            return ModelCatalogEntry.publishInitial(
                    provider,
                    ModelCatalogEntryId.generate(),
                    new ModelId("deepseek-v4-flash"),
                    new ModelRevision("DeepSeek-V4-Flash-0731"),
                    "DeepSeek V4 Flash",
                    128_000,
                    8_192,
                    Set.of(
                            new ModelCapability("text.generation"),
                            new ModelCapability("tool-calling")),
                    Set.of(new ModelRegion("global"), new ModelRegion("cn")),
                    ACTOR,
                    CREATED_AT);
        }
    }
}
