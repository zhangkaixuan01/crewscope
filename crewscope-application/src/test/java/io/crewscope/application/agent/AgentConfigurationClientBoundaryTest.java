package io.crewscope.application.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentModelBindingKind;
import io.crewscope.domain.agent.AgentModelDefault;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.model.ModelCatalogEntryId;
import io.crewscope.domain.model.ModelCatalogRevision;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AgentConfigurationClientBoundaryTest {

    @Test
    void exposesOnlyStableIdsAndExplicitSafeConfigurationFields() {
        assertEquals(
                List.of("connectionId", "catalogEntryId", "catalogRevision"),
                componentNames(AgentModelSelectionDraft.class));
        assertEquals(
                List.of(
                        "personalModelBinding",
                        "teamModelBinding",
                        "supplementalInstructions",
                        "approvedSkillKeys",
                        "memoryPolicy",
                        "budgetPolicy",
                        "generateOptions"),
                componentNames(AgentConfigurationDraft.class));
        Set<String> forbiddenNames = Set.of(
                "provider",
                "adapter",
                "modelid",
                "displayname",
                "hash",
                "promptbaseline",
                "tool",
                "schema",
                "policypack",
                "endpoint",
                "credential",
                "secret",
                "header",
                "baseurl");
        assertTrue(Arrays.stream(AgentConfigurationDraft.class.getRecordComponents())
                .map(RecordComponent::getName)
                .map(String::toLowerCase)
                .noneMatch(name -> forbiddenNames.stream().anyMatch(name::contains)));
        assertTrue(Arrays.stream(SafeModelGenerateOptions.class.getRecordComponents())
                .map(RecordComponent::getType)
                .noneMatch(type -> Map.class.isAssignableFrom(type) || type.equals(Object.class)));
    }

    @Test
    void clientCannotDeclareOrchestrationOrPersonalDefaultInheritance() {
        AgentModelSelectionDraft selection = new AgentModelSelectionDraft(
                ModelConnectionId.generate(),
                ModelCatalogEntryId.generate(),
                new ModelCatalogRevision(1));
        AgentModelBindingDraft direct = AgentModelBindingDraft.direct(
                selection, Optional.empty());
        AgentConfigurationDraft accepted = new AgentConfigurationDraft(
                Optional.of(direct),
                Optional.of(AgentModelBindingDraft.inheritTeamDefault()),
                Optional.of("  review transaction boundaries  "),
                Set.of("java-review"),
                Optional.empty(),
                Optional.empty(),
                SafeModelGenerateOptions.defaults());

        assertEquals(
                Optional.of("review transaction boundaries"),
                accepted.supplementalInstructions());
        assertThrows(
                DomainValidationException.class,
                () -> new AgentConfigurationDraft(
                        Optional.of(AgentModelBindingDraft.inheritTeamDefault()),
                        Optional.empty(),
                        Optional.empty(),
                        Set.of(),
                        Optional.empty(),
                        Optional.empty(),
                        SafeModelGenerateOptions.defaults()));
        assertThrows(
                DomainValidationException.class,
                () -> new AgentModelBindingDraft(
                        AgentModelBindingKind.ORCHESTRATION_ONLY,
                        Optional.empty(),
                        Optional.empty()));
    }

    @Test
    void repositoryPortsPersistOnlyTrustedDomainFacts() {
        Set<Class<?>> configurationTypes = Arrays.stream(
                        AgentConfigurationRepository.class.getMethods())
                .filter(method -> method.getDeclaringClass()
                        .equals(AgentConfigurationRepository.class))
                .flatMap(method -> methodTypes(method).stream())
                .collect(Collectors.toSet());
        Set<Class<?>> defaultTypes = Arrays.stream(AgentModelDefaultRepository.class.getMethods())
                .filter(method -> method.getDeclaringClass().equals(AgentModelDefaultRepository.class))
                .flatMap(method -> methodTypes(method).stream())
                .collect(Collectors.toSet());

        assertTrue(configurationTypes.contains(AgentConfigurationVersion.class));
        assertTrue(defaultTypes.contains(AgentModelDefault.class));
        assertFalse(configurationTypes.contains(AgentConfigurationDraft.class));
        assertFalse(defaultTypes.contains(AgentModelBindingDraft.class));
        assertTrue(configurationTypes.stream().noneMatch(Map.class::isAssignableFrom));
        assertTrue(defaultTypes.stream().noneMatch(Map.class::isAssignableFrom));
    }

    private static List<String> componentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    private static List<Class<?>> methodTypes(Method method) {
        java.util.ArrayList<Class<?>> types = new java.util.ArrayList<>();
        types.add(method.getReturnType());
        types.addAll(Arrays.asList(method.getParameterTypes()));
        return types;
    }
}
