package io.crewscope.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.crewscope.domain.agent.ResolvedModelRole;
import io.crewscope.domain.model.ModelCallAttribution;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelProviderKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * M10-F03 realtime attribution announcements: every attributed stream start publishes the
 * serving instance's coordinates to the Task observation scope, and env-slot models announce
 * empty so their usage stays reported without a fact.
 */
class AgentScopeModelAttributionTest {

    private static final ModelProviderKey PROVIDER = new ModelProviderKey("deepseek");
    private static final ModelId MODEL = new ModelId("deepseek-v4-flash");

    @Test
    void attributedStreamAnnouncesItsCoordinatesToTheTaskObservationScope() {
        ModelCallAttribution attribution = ModelCallAttribution.chat(
                PROVIDER, MODEL, ModelConnectionId.generate(), 4L,
                ResolvedModelRole.PRIMARY);
        List<Optional<ModelCallAttribution>> announced = new ArrayList<>();
        Model model = new ObservableAgentScopeModel(
                respondingModel(), AgentModelRole.PRIMARY, null, attribution);

        model.stream(List.of(), List.of(), GenerateOptions.builder().build())
                .contextWrite(TaskAgentCallObservationScope.install(
                        ignored -> { }, announced::add))
                .blockLast();

        assertEquals(List.of(Optional.of(attribution)), announced);
    }

    @Test
    void envSlotFallbackStreamAnnouncesEmptyCoordinates() {
        List<Optional<ModelCallAttribution>> announced = new ArrayList<>();
        Model fallback = new ObservableAgentScopeModel(
                respondingModel(), AgentModelRole.FALLBACK);

        fallback.stream(List.of(), List.of(), GenerateOptions.builder().build())
                .contextWrite(TaskAgentCallObservationScope.install(
                        ignored -> { }, announced::add))
                .blockLast();

        assertEquals(List.of(Optional.empty()), announced);
    }

    private static Model respondingModel() {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.just(ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("ok").build()))
                        .usage(new ChatUsage(3, 2, 0.01))
                        .finishReason("stop")
                        .build());
            }

            @Override
            public String getModelName() {
                return "attribution-fixture-model";
            }
        };
    }
}
