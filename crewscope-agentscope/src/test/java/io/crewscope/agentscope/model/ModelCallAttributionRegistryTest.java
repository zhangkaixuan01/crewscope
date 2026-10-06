package io.crewscope.agentscope.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.model.Model;
import io.crewscope.domain.agent.ResolvedModelRole;
import io.crewscope.domain.model.ModelCallAttribution;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelUsageRole;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * M10-F03 chat attribution registry: instance→coordinates resolution is reference-scoped and
 * call-local, the whole pinned pair registers in one step, and the resolved-role vocabulary
 * maps onto the usage-ledger roles. Pairs with a fallback resolve through the active model
 * name, because the SDK hands the middleware a per-call switcher instance.
 */
class ModelCallAttributionRegistryTest {

    @Test
    void registeredInstancesResolveTheirAttributionAndForeignInstancesStayEmpty() {
        Model pinned = mock(Model.class);
        ModelCallAttributionRegistry registry = new ModelCallAttributionRegistry()
                .register(pinned, attribution(ResolvedModelRole.PRIMARY));

        assertEquals(ModelUsageRole.CHAT_PRIMARY,
                registry.resolve(pinned).orElseThrow().role());
        assertTrue(registry.resolve(mock(Model.class)).isEmpty());
        assertTrue(registry.resolve(null).isEmpty());

        ModelCallAttributionRegistry empty = new ModelCallAttributionRegistry();
        assertTrue(empty.resolve(pinned).isEmpty());
    }

    @Test
    void registerAllAddsTheWholePinnedPair() {
        Model primary = mock(Model.class);
        Model fallback = mock(Model.class);
        ResolvedAgentScopeModels pair = new ResolvedAgentScopeModels(
                primary,
                Optional.of(fallback),
                Optional.of(attribution(ResolvedModelRole.PRIMARY)),
                Optional.of(attribution(ResolvedModelRole.FALLBACK)));

        ModelCallAttributionRegistry registry =
                new ModelCallAttributionRegistry().registerAll(pair);

        assertEquals(ModelUsageRole.CHAT_PRIMARY, registry.resolve(primary)
                .orElseThrow().role());
        assertEquals(ModelUsageRole.CHAT_FALLBACK, registry.resolve(fallback)
                .orElseThrow().role());
    }

    @Test
    void perCallFallbackSwitchersResolveThroughTheActiveModelName() {
        Model primary = mock(Model.class);
        when(primary.getModelName()).thenReturn("deepseek-flash");
        Model fallback = mock(Model.class);
        when(fallback.getModelName()).thenReturn("deepseek-chat");
        ModelCallAttributionRegistry registry = new ModelCallAttributionRegistry()
                .register(primary, attribution(ResolvedModelRole.PRIMARY))
                .register(fallback, attribution(ResolvedModelRole.FALLBACK));
        // ReActAgent builds a fresh switcher wrapper around a fallback pair on every call;
        // its getModelName() delegates to whichever instance is currently active.
        AtomicReference<Model> active = new AtomicReference<>(primary);
        Model switcher = mock(Model.class);
        when(switcher.getModelName())
                .thenAnswer(invocation -> active.get().getModelName());

        assertEquals(ModelUsageRole.CHAT_PRIMARY, registry.resolve(switcher)
                .orElseThrow().role());
        active.set(fallback);
        assertEquals(ModelUsageRole.CHAT_FALLBACK, registry.resolve(switcher)
                .orElseThrow().role());
    }

    @Test
    void anAmbiguousModelNameResolvesToNothingRatherThanGuessing() {
        Model first = mock(Model.class);
        when(first.getModelName()).thenReturn("deepseek-flash");
        Model second = mock(Model.class);
        when(second.getModelName()).thenReturn("deepseek-flash");
        ModelCallAttributionRegistry registry = new ModelCallAttributionRegistry()
                .register(first, attribution(ResolvedModelRole.PRIMARY))
                .register(second, attribution(ResolvedModelRole.FALLBACK));
        Model wrapper = mock(Model.class);
        when(wrapper.getModelName()).thenReturn("deepseek-flash");

        assertTrue(registry.resolve(wrapper).isEmpty());
    }

    @Test
    void reRegisteringTheSameInstanceOverwritesByReference() {
        Model shared = mock(Model.class);
        ModelCallAttributionRegistry registry = new ModelCallAttributionRegistry()
                .register(shared, attribution(ResolvedModelRole.PRIMARY));

        // A mid-call rebuild (structured-output recovery) reuses the cached instance with
        // fresh coordinates; the later registration must win for the rest of the call.
        registry.register(shared, attribution(ResolvedModelRole.FALLBACK));

        assertEquals(ModelUsageRole.CHAT_FALLBACK, registry.resolve(shared)
                .orElseThrow().role());
    }

    @Test
    void legacyPairsCarryNoAttributionAndRegisterNothing() {
        Model primary = mock(Model.class);
        ResolvedAgentScopeModels legacy = new ResolvedAgentScopeModels(
                primary, Optional.empty());
        ModelCallAttributionRegistry registry = new ModelCallAttributionRegistry();

        registry.registerAll(legacy);

        assertTrue(registry.resolve(primary).isEmpty());
        // The pair shape itself still agrees: a fallback without coordinates is legal.
        assertTrue(legacy.primaryAttribution().isEmpty());
        assertTrue(legacy.fallbackAttribution().isEmpty());
    }

    @Test
    void chatRoleMappingCoversPrimaryAndFallbackOnly() {
        assertEquals(ModelUsageRole.CHAT_PRIMARY,
                ModelCallAttribution.chat(
                        new ModelProviderKey("deepseek"),
                        new ModelId("deepseek-flash"),
                        new ModelConnectionId(UUID.randomUUID()),
                        2L,
                        ResolvedModelRole.PRIMARY).role());
        assertEquals(ModelUsageRole.CHAT_FALLBACK,
                ModelCallAttribution.chat(
                        new ModelProviderKey("deepseek"),
                        new ModelId("deepseek-chat"),
                        new ModelConnectionId(UUID.randomUUID()),
                        0L,
                        ResolvedModelRole.FALLBACK).role());
    }

    @Test
    void negativeConnectionVersionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ModelCallAttribution(
                new ModelProviderKey("deepseek"),
                new ModelId("deepseek-flash"),
                new ModelConnectionId(UUID.randomUUID()),
                -1L,
                ModelUsageRole.CHAT_PRIMARY));
        assertThrows(NullPointerException.class, () -> new ModelCallAttribution(
                new ModelProviderKey("deepseek"),
                new ModelId("deepseek-flash"),
                new ModelConnectionId(UUID.randomUUID()),
                1L,
                null));
    }

    private static ModelCallAttribution attribution(ResolvedModelRole role) {
        return ModelCallAttribution.chat(
                new ModelProviderKey("deepseek"),
                new ModelId("deepseek-flash"),
                new ModelConnectionId(UUID.randomUUID()),
                3L,
                role);
    }
}
