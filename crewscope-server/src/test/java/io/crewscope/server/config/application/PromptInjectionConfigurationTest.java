package io.crewscope.server.config.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

import io.crewscope.application.memory.AgentMemoryService;
import io.crewscope.application.retrieval.InjectionManifestRepository;
import io.crewscope.application.retrieval.KnowledgeRetrievalService;
import io.crewscope.application.retrieval.PromptInjectionService;
import io.crewscope.agentscope.coding.InjectionPromptRenderer;
import io.crewscope.domain.shared.time.TimeProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * M10-I02b assembly contract: the injection service and renderer always assemble —
 * the switch lives inside the service — the frozen budget defaults ride the
 * properties, and an incoherent budget (total past the 32768 ceiling, or layer sums
 * past the total) refuses startup instead of silently overflowing the bound.
 */
class PromptInjectionConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PromptInjectionConfiguration.class)
            .withBean(KnowledgeRetrievalService.class,
                    () -> mock(KnowledgeRetrievalService.class))
            .withBean(AgentMemoryService.class, () -> mock(AgentMemoryService.class))
            .withBean(InjectionManifestRepository.class,
                    () -> mock(InjectionManifestRepository.class))
            .withBean(TimeProvider.class,
                    () -> () -> io.crewscope.domain.shared.time.UtcTimestamp.parse(
                            "2026-10-04T11:00:00Z"));

    @Test
    void theServiceAndRendererAlwaysAssembleWithTheFrozenDefaults() {
        runner.run(context -> {
            context.assertThat()
                    .hasNotFailed()
                    .hasSingleBean(PromptInjectionService.class)
                    .hasSingleBean(InjectionPromptRenderer.class);

            InjectionBudgetProperties properties =
                    context.getBean(InjectionBudgetProperties.class);
            assertFalse(properties.isEnabled());
            assertEquals(8192, properties.getTotalTokens());
            assertEquals(3072, properties.getKnowledgeTokens());
            assertEquals(4096, properties.getChunkTokens());
            assertEquals(1024, properties.getMemoryTokens());
        });
    }

    @Test
    void aTotalPastTheCeilingRefusesStartup() {
        runner.withPropertyValues("crewscope.knowledge.injection.total-tokens=32769")
                .run(context -> context.assertThat().hasFailed());
    }

    @Test
    void aZeroTotalRefusesStartup() {
        runner.withPropertyValues("crewscope.knowledge.injection.total-tokens=0")
                .run(context -> context.assertThat().hasFailed());
    }

    @Test
    void layerSumsPastTheTotalRefuseStartup() {
        runner.withPropertyValues(
                        "crewscope.knowledge.injection.total-tokens=8191")
                .run(context -> context.assertThat().hasFailed());
    }

    @Test
    void aNegativeLayerBudgetRefusesStartup() {
        runner.withPropertyValues(
                        "crewscope.knowledge.injection.memory-tokens=-1")
                .run(context -> context.assertThat().hasFailed());
    }

    @Test
    void aCoherentCustomBudgetAssembles() {
        runner.withPropertyValues(
                        "crewscope.knowledge.injection.enabled=true",
                        "crewscope.knowledge.injection.total-tokens=2048",
                        "crewscope.knowledge.injection.knowledge-tokens=1024",
                        "crewscope.knowledge.injection.chunk-tokens=1024",
                        "crewscope.knowledge.injection.memory-tokens=0")
                .run(context -> {
                    context.assertThat().hasNotFailed();
                    InjectionBudgetProperties properties =
                            context.getBean(InjectionBudgetProperties.class);
                    assertEquals(2048, properties.getTotalTokens());
                    assertEquals(0, properties.getMemoryTokens());
                });
    }
}
