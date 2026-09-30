package io.crewscope.agentscope.model;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.util.JsonUtils;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Flux;

/**
 * Defect 18 (M9b-Q02): DeepSeek's thinking mode rejects every forced tool_choice with 400
 * ("Thinking mode does not support this tool_choice"), and the agentscope 2.0.0 OpenAI
 * adapter exposes no request-body extension point (GenerateOptions.additionalBodyParams are
 * never serialized), so the forced structured-delivery call of defect 11's mitigation died
 * whenever the degraded unforced retry answered in prose — request-level degradation cannot
 * force a structured tool answer, and the retry loop exhausted into CODING_RUNTIME_FAILED.
 *
 * <p>A forced tool_choice only ever appears on the bounded structured-delivery call, whose
 * whole job is answering through the generated tool — precision a thinking pass was never
 * needed for. This transport rewrites exactly those requests (a JSON-object tool_choice and
 * no explicit thinking field) to disable thinking for that single call. Tool sessions
 * ("auto"/"none"/absent), compaction summaries and plain chat pass through untouched, and a
 * body that fails to parse passes through unchanged rather than breaking the call.
 */
final class ThinkingModeToolChoiceTransport implements HttpTransport {

    private static final Map<String, Object> THINKING_DISABLED =
            Map.of("type", "disabled");

    private final HttpTransport delegate;

    ThinkingModeToolChoiceTransport(HttpTransport delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public HttpResponse execute(HttpRequest request) throws HttpTransportException {
        return delegate.execute(rewritten(request));
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        return delegate.stream(rewritten(request));
    }

    @Override
    public void close() {
        delegate.close();
    }

    /** Rewrites the request only when it carries a forced (JSON-object) tool_choice. */
    private HttpRequest rewritten(HttpRequest request) {
        String body = request.getBody();
        if (body == null || !body.contains("tool_choice")) {
            return request;
        }
        Map<String, Object> parsed;
        try {
            if (JsonUtils.getJsonCodec().fromJson(body, Object.class)
                    instanceof Map<?, ?> tree) {
                parsed = new LinkedHashMap<>();
                tree.forEach((key, value) -> parsed.put(String.valueOf(key), value));
            } else {
                return request;
            }
        } catch (RuntimeException malformed) {
            return request;
        }
        if (!(parsed.get("tool_choice") instanceof Map) || parsed.containsKey("thinking")) {
            return request;
        }
        parsed.put("thinking", THINKING_DISABLED);
        return HttpRequest.builder()
                .url(request.getUrl())
                .method(request.getMethod())
                .headers(request.getHeaders())
                .body(JsonUtils.getJsonCodec().toJson(parsed))
                .build();
    }
}
