package io.crewscope.application.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplateKey;
import io.crewscope.domain.agent.AgentTemplatePolicy;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.agent.AgentTemplateVersion;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DefaultAgentTemplateCatalogInitializerTest {

    private static final OrganizationId ORGANIZATION = OrganizationId.generate();
    private static final PrincipalId ACTOR = PrincipalId.generate();
    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-31T00:00:00Z");

    @Test
    void restoresTheFiveBuiltInTemplatesExactlyOnce() {
        RecordingRepository repository = new RecordingRepository();
        DefaultAgentTemplateCatalogInitializer initializer =
                new DefaultAgentTemplateCatalogInitializer(repository, new ObjectMapper());

        initializer.initialize(ORGANIZATION, ACTOR, NOW);
        initializer.initialize(ORGANIZATION, ACTOR, NOW);

        assertEquals(
                List.of(
                        "coding",
                        "personal-assistant",
                        "reviewer",
                        "team-coordinator",
                        "team-observer"),
                repository.values.stream()
                        .map(value -> value.templateVersion().key().value())
                        .sorted()
                        .toList());
        assertEquals(5, repository.appendCount);
        assertTrue(repository.values.stream().allMatch(value -> value.status().name().equals("ACTIVE")));
        assertTrue(repository.values.stream()
                .filter(value -> value.templateVersion().key().equals(new AgentTemplateKey("reviewer")))
                .findFirst()
                .orElseThrow()
                .policy()
                .structuredOutputSchema()
                .isPresent());
    }

    @Test
    void acceptsAnEquivalentDefinitionCommittedByAConcurrentInitializer() {
        ConcurrentWinnerRepository repository = new ConcurrentWinnerRepository();
        DefaultAgentTemplateCatalogInitializer initializer =
                new DefaultAgentTemplateCatalogInitializer(repository, new ObjectMapper());

        initializer.initialize(ORGANIZATION, ACTOR, NOW);

        assertEquals(5, repository.values.size());
        assertEquals(5, repository.appendCount);
    }

    @Test
    void failsClosedWhenAnExistingBuiltInCoordinateHasDifferentContent() {
        RecordingRepository repository = new RecordingRepository();
        DefaultAgentTemplateCatalogInitializer initializer =
                new DefaultAgentTemplateCatalogInitializer(repository, new ObjectMapper());
        initializer.initialize(ORGANIZATION, ACTOR, NOW);
        AgentTemplateDefinition coding = repository.values.stream()
                .filter(value -> value.templateVersion().key().equals(new AgentTemplateKey("coding")))
                .findFirst()
                .orElseThrow();
        AgentTemplatePolicy driftedPolicy = AgentTemplatePolicy.define(
                "A locally modified built-in prompt.",
                coding.policy().allowedTools(),
                coding.policy().approvedSkillKeys(),
                coding.policy().structuredOutputSchema(),
                coding.policy().memberConfigurableSlots(),
                coding.policy().administratorConfigurableSlots());
        AgentTemplateDefinition drifted = AgentTemplateDefinition.publishInitial(
                coding.publisherScope(),
                coding.templateVersion().key(),
                coding.runtimeRole(),
                coding.allowedOwnershipTypes(),
                coding.allowedExecutionScopes(),
                coding.capabilities(),
                driftedPolicy,
                ACTOR,
                NOW);
        repository.values.remove(coding);
        repository.values.add(drifted);

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> initializer.initialize(ORGANIZATION, ACTOR, NOW));

        assertEquals("agentTemplate.contentHash", failure.error().details().get("field"));
    }

    private static class RecordingRepository implements AgentTemplateRepository {
        protected final List<AgentTemplateDefinition> values = new ArrayList<>();
        protected int appendCount;

        @Override
        public AgentTemplateDefinition append(AgentTemplateDefinition definition) {
            appendCount++;
            values.add(definition);
            return definition;
        }

        @Override
        public AgentTemplateDefinition updateLifecycle(AgentTemplateDefinition definition) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<AgentTemplateDefinition> findByVersion(
                AgentTemplatePublisherScope scope, AgentTemplateVersion version) {
            return values.stream()
                    .filter(value -> value.publisherScope().equals(scope)
                            && value.templateVersion().equals(version))
                    .findFirst();
        }

        @Override
        public Optional<AgentTemplateDefinition> findLatest(
                AgentTemplatePublisherScope scope, AgentTemplateKey key) {
            return values.stream()
                    .filter(value -> value.publisherScope().equals(scope)
                            && value.templateVersion().key().equals(key))
                    .findFirst();
        }

        @Override
        public List<AgentTemplateDefinition> findPage(
                AgentTemplatePublisherScope scope, int offset, int limit) {
            return values.stream().filter(value -> value.publisherScope().equals(scope)).toList();
        }
    }

    private static final class ConcurrentWinnerRepository extends RecordingRepository {
        private boolean firstAppend = true;

        @Override
        public AgentTemplateDefinition append(AgentTemplateDefinition definition) {
            AgentTemplateDefinition committed = super.append(definition);
            if (firstAppend) {
                firstAppend = false;
                throw new DomainValidationException(
                        "agentTemplate.version", "conflicts with the committed template stream");
            }
            return committed;
        }
    }
}
