package io.crewscope.agentscope;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;

/**
 * Deterministic test model used by AgentScope runtime integration tests.
 *
 * <p>The fixture avoids network calls and records every model input so tests can prove that a
 * later HarnessAgent invocation restored the earlier conversation from {@code AgentStateStore}.
 */
final class ScriptedModel implements Model {

    private final List<ChatResponse> responses;
    private final AtomicInteger callCount = new AtomicInteger();
    private final List<List<Msg>> requests = new ArrayList<>();

    ScriptedModel(String... responses) {
        this.responses =
                Arrays.stream(responses).map(ScriptedModel::textResponse).toList();
        validateResponses();
    }

    ScriptedModel(ChatResponse... responses) {
        this.responses = List.of(responses);
        validateResponses();
    }

    private void validateResponses() {
        if (this.responses.isEmpty()) {
            throw new IllegalArgumentException("At least one scripted response is required");
        }
        this.responses.forEach(response -> Objects.requireNonNull(response, "response"));
    }

    @Override
    public synchronized Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        int invocation = callCount.getAndIncrement();
        if (invocation >= responses.size()) {
            return Flux.error(
                    new IllegalStateException(
                            "No scripted response for model invocation " + invocation));
        }

        requests.add(List.copyOf(messages));
        return Flux.just(responses.get(invocation));
    }

    @Override
    public String getModelName() {
        return "crewscope-scripted-model";
    }

    int callCount() {
        return callCount.get();
    }

    synchronized List<Msg> request(int index) {
        return requests.get(index);
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .usage(new ChatUsage(10, 4, 0.01))
                .finishReason("stop")
                .build();
    }
}
