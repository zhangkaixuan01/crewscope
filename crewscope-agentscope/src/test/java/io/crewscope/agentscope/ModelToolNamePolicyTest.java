package io.crewscope.agentscope;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.crewscope.agentscope.teamobserver.TeamObserverToolNames;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Provider compatibility gate for every model-facing Tool schema. */
class ModelToolNamePolicyTest {

    @Test
    void acceptsCurrentObserverAliasesAndRejectsDottedLongOrDuplicateNames() {
        assertDoesNotThrow(() -> ModelToolNamePolicy.requireCompatibleSchemas(null));
        assertDoesNotThrow(() -> ModelToolNamePolicy.requireCompatibleNames(
                TeamObserverToolNames.runtimeNames()));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelToolNamePolicy.requireCompatibleNames(Set.of("team.activity.read")));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelToolNamePolicy.requireCompatibleNames(Set.of("a".repeat(65))));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelToolNamePolicy.requireCompatibleSchemas(List.of(
                        schema("repository_read"), schema("repository_read"))));
        assertEquals(
                Set.of("repository_read", "review_context_read", "shell_exec"),
                ModelToolNamePolicy.runtimeAliases(Set.of(
                        "repository.read", "review.context.read", "shell.exec")));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelToolNamePolicy.runtimeAliases(Set.of(
                        "repository.read", "repository_read")));
    }

    @Test
    void observableModelStopsInvalidToolNamesBeforeCallingAnyProvider() {
        Model provider = mock(Model.class);
        ObservableAgentScopeModel model = new ObservableAgentScopeModel(
                provider, AgentModelRole.PRIMARY);

        assertThrows(
                IllegalArgumentException.class,
                () -> model.stream(
                                List.of(),
                                List.of(schema("repository.read")),
                                GenerateOptions.builder().build())
                        .collectList()
                        .block());

        verifyNoInteractions(provider);
    }

    private static ToolSchema schema(String name) {
        return ToolSchema.builder()
                .name(name)
                .description("test")
                .parameters(Map.of("type", "object"))
                .build();
    }
}
