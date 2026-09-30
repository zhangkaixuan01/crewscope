package io.crewscope.agentscope.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

class ThinkingModeToolChoiceTransportTest {

    private final HttpTransport delegate = mock(HttpTransport.class);
    private final ThinkingModeToolChoiceTransport transport =
            new ThinkingModeToolChoiceTransport(delegate);

    @Test
    void forcedToolChoiceGainsThinkingDisabledForThatCall() throws Exception {
        when(delegate.execute(any())).thenReturn(mock(HttpResponse.class));
        String body = "{\"model\":\"deepseek-flash\",\"messages\":[],\"tools\":[],"
                + "\"tool_choice\":{\"type\":\"function\",\"function\":{\"name\":"
                + "\"generate_response\"}}}";

        transport.execute(request(body));

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(delegate).execute(captor.capture());
        Map<?, ?> sent = (Map<?, ?>) io.agentscope.core.util.JsonUtils.getJsonCodec()
                .fromJson(captor.getValue().getBody(), Object.class);
        assertEquals(Map.of("type", "disabled"), sent.get("thinking"));
        assertEquals("generate_response",
                ((Map<?, ?>) ((Map<?, ?>) sent.get("tool_choice")).get("function"))
                        .get("name"));
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                captor.getValue().getUrl());
    }

    @Test
    void autoToolChoiceToolSessionsAndCompactionBodiesPassThroughUntouched() throws Exception {
        when(delegate.execute(any())).thenReturn(mock(HttpResponse.class));

        transport.execute(request("{\"model\":\"deepseek-flash\",\"tool_choice\":\"auto\"}"));
        transport.execute(request("{\"model\":\"deepseek-flash\",\"tool_choice\":\"none\"}"));
        transport.execute(request("{\"model\":\"deepseek-flash\",\"messages\":[]}"));
        transport.execute(request(null));

        org.mockito.Mockito.verify(delegate, org.mockito.Mockito.times(4))
                .execute(org.mockito.ArgumentMatchers.argThat(
                        sent -> sent.getBody() == null || !sent.getBody().contains("thinking")));
    }

    @Test
    void anExplicitThinkingFieldIsNeverOverridden() throws Exception {
        when(delegate.execute(any())).thenReturn(mock(HttpResponse.class));

        transport.execute(request("{\"tool_choice\":{\"type\":\"function\"},"
                + "\"thinking\":{\"type\":\"enabled\"}}"));

        org.mockito.Mockito.verify(delegate).execute(
                org.mockito.ArgumentMatchers.argThat(
                        sent -> sent.getBody().contains("\"enabled\"")));
    }

    @Test
    void aBodyThatFailsToParsePassesThroughUnchanged() throws Exception {
        when(delegate.execute(any())).thenReturn(mock(HttpResponse.class));
        when(delegate.stream(any())).thenReturn(Flux.empty());
        String malformed = "{\"tool_choice\": not-json";

        transport.execute(request(malformed));
        transport.stream(request(malformed));

        org.mockito.Mockito.verify(delegate).execute(
                org.mockito.ArgumentMatchers.argThat(
                        sent -> malformed.equals(sent.getBody())));
        org.mockito.Mockito.verify(delegate).stream(
                org.mockito.ArgumentMatchers.argThat(
                        sent -> malformed.equals(sent.getBody())));
    }

    @Test
    void streamPathCarriesTheSameRewrite() {
        when(delegate.stream(any())).thenReturn(Flux.empty());

        transport.stream(request("{\"tool_choice\":{\"type\":\"function\"}}"));

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(delegate).stream(captor.capture());
        assertTrue(captor.getValue().getBody().contains("disabled"));
        assertFalse(captor.getValue().getBody().contains("\"tool_choice\":{}"));
    }

    private static HttpRequest request(String body) {
        return HttpRequest.builder()
                .url("https://api.deepseek.com/v1/chat/completions")
                .method("POST")
                .headers(Map.of("Content-Type", "application/json"))
                .body(body)
                .build();
    }
}
