package io.crewscope.server.config.application;

import io.crewscope.agentscope.coding.CodingSpecialistSkillBundle;
import io.crewscope.agentscope.coding.InjectionPromptRenderer;
import io.crewscope.application.memory.AgentMemoryService;
import io.crewscope.application.retrieval.InjectionManifestRepository;
import io.crewscope.application.retrieval.KnowledgeRetrievalService;
import io.crewscope.application.retrieval.PromptInjectionService;
import io.crewscope.domain.retrieval.ManifestSourceRef;
import io.crewscope.domain.retrieval.ManifestSourceStage;
import io.crewscope.domain.retrieval.ManifestSourceType;
import io.crewscope.domain.shared.time.TimeProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Server composition for prompt injection (M10-I02b). The service always assembles —
 * the injection switch is a boolean inside it, mirroring {@link
 * KnowledgeRetrievalConfiguration} — so enabling it is a flip, not a restart-with-beans.
 * The memory switch rides the same {@code crewscope.memory.enabled} key as the memory
 * lifecycle itself: one key, one meaning, and a switched-off memory deployment never
 * gains a model-facing read through the back door. The skill instruction reference is
 * pinned here to the same classpath bundle constants the Coding runtime verifies, so
 * the manifest and the loaded skill cannot drift apart.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InjectionBudgetProperties.class)
public class PromptInjectionConfiguration {

    @Bean
    PromptInjectionService promptInjectionService(
            KnowledgeRetrievalService retrieval,
            AgentMemoryService memory,
            InjectionManifestRepository manifests,
            InjectionBudgetProperties injection,
            TimeProvider timeProvider,
            @Value("${crewscope.memory.enabled:false}") boolean memoryEnabled) {
        return new PromptInjectionService(
                retrieval,
                memory,
                manifests,
                injection.isEnabled(),
                memoryEnabled,
                injection.validatedLimits(),
                skillInstruction(),
                timeProvider);
    }

    @Bean
    InjectionPromptRenderer injectionPromptRenderer() {
        return new InjectionPromptRenderer();
    }

    /** Hard-retained, always INJECTED, sourced from the verified classpath skill bundle. */
    private static ManifestSourceRef skillInstruction() {
        return new ManifestSourceRef(
                ManifestSourceType.SKILL_INSTRUCTION,
                CodingSpecialistSkillBundle.SKILL_ID,
                1,
                CodingSpecialistSkillBundle.SHA_256,
                ManifestSourceStage.INJECTED);
    }
}
