package io.crewscope.server.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.DeepSeekFormatter;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import io.agentscope.harness.agent.HarnessAgent;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/** AgentScope 2.0.0 capability and isolation evidence for M5-S01. */
class AgentScopeDynamicModelM5S01IntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Duration CALL_TIMEOUT = Duration.ofSeconds(15);

  @TempDir Path workspace;

  @Test
  void springAdapterRegistryKeepsDeepSeekAndOpenAiConnectionsIsolated() throws Exception {
    try (ProviderStub deepSeek = ProviderStub.start(index -> switch (index) {
           case 1 -> StubResponse.failure(500);
           case 2 -> StubResponse.ok(toolCall("deepseek-model", "deepseek-tool", "connection_probe"));
           case 3 -> StubResponse.ok(structuredToolCall(
               "deepseek-model", "deepseek-result", "deepseek", "deepseek-connection"));
           default -> StubResponse.failure(500);
         });
         ProviderStub openAi = ProviderStub.start(index -> switch (index) {
           case 1 -> StubResponse.ok(toolCall("openai-model", "openai-tool", "connection_probe"));
           case 2 -> StubResponse.ok(nativeStructuredResponse(
               "openai-model", "openai", "openai-connection"));
           default -> StubResponse.failure(500);
         })) {
      new ApplicationContextRunner()
          .withUserConfiguration(ProbeAdapterConfiguration.class)
          .run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(Model.class);
            ProbeModelFactory factory = context.getBean(ProbeModelFactory.class);

            Model deepSeekModel = factory.create(request(
                "deepseek", "deepseek-connection", deepSeek.baseUri(),
                "deepseek-key", "deepseek-model"));
            Model openAiModel = factory.create(request(
                "openai", "openai-connection", openAi.baseUri(),
                "openai-key", "openai-model"));

            ProbeResult deepSeekResult = callAgent(
                "deepseek", deepSeekModel, "deepseek-connection", 2, null);
            ProbeResult openAiResult = callAgent(
                "openai", openAiModel, "openai-connection", 2, null);

            assertEquals(new ProbeResult("deepseek", "deepseek-connection"), deepSeekResult);
            assertEquals(new ProbeResult("openai", "openai-connection"), openAiResult);
          });

      assertConnection(deepSeek.requests(), "deepseek-key", "deepseek-model");
      assertConnection(openAi.requests(), "openai-key", "openai-model");
      assertEquals(3, deepSeek.requests().size());
      assertEquals(2, openAi.requests().size());

      String deepSeekFinalRequest = deepSeek.requests().get(2).body();
      assertThat(deepSeekFinalRequest)
          .contains("deepseek-connection", "generate_response")
          .doesNotContain("response_format", "openai-key", "openai-model");

      String openAiFinalRequest = openAi.requests().get(1).body();
      JsonNode openAiPayload = JSON.readTree(openAiFinalRequest);
      assertThat(openAiFinalRequest)
          .contains("openai-connection")
          .doesNotContain("deepseek-key", "deepseek-model");
      assertThat(openAiPayload.has("response_format")).isTrue();
      assertThat(toolNames(openAiPayload)).doesNotContain("generate_response");
    }
  }

  @Test
  void fallbackUsesItsOwnConnectionAfterPrimaryRetryBudgetIsExhausted() throws Exception {
    try (ProviderStub primary = ProviderStub.start(ignored -> StubResponse.failure(503));
         ProviderStub fallback = ProviderStub.start(index -> switch (index) {
           case 1 -> StubResponse.ok(toolCall("fallback-model", "fallback-tool", "connection_probe"));
           case 2 -> StubResponse.ok(structuredToolCall(
               "fallback-model", "fallback-result", "fallback", "fallback-connection"));
           default -> StubResponse.failure(500);
         })) {
      new ApplicationContextRunner()
          .withUserConfiguration(ProbeAdapterConfiguration.class)
          .run(context -> {
            ProbeModelFactory factory = context.getBean(ProbeModelFactory.class);
            Model primaryModel = factory.create(request(
                "deepseek", "primary-connection", primary.baseUri(),
                "primary-key", "primary-model"));
            Model fallbackModel = factory.create(request(
                "openai", "fallback-connection", fallback.baseUri(),
                "fallback-key", "fallback-model"));

            ProbeResult result = callAgent(
                "fallback", primaryModel, "fallback-connection", 1, fallbackModel);

            assertEquals(new ProbeResult("fallback", "fallback-connection"), result);
          });

      // Each reasoning round first exhausts the primary once, then uses the independently built
      // fallback. No request can carry the other Connection's endpoint, key, or model name.
      assertEquals(2, primary.requests().size());
      assertEquals(2, fallback.requests().size());
      assertConnection(primary.requests(), "primary-key", "primary-model");
      assertConnection(fallback.requests(), "fallback-key", "fallback-model");
      assertThat(fallback.requests().get(1).body())
          .contains("fallback-connection", "generate_response")
          .doesNotContain("primary-key", "primary-model");
    }
  }

  private ProbeResult callAgent(
      String suffix,
      Model model,
      String marker,
      int maxAttempts,
      Model fallbackModel) {
    ConnectionProbeTool tool = new ConnectionProbeTool(marker);
    Toolkit toolkit = new Toolkit();
    toolkit.registerAgentTool(tool);
    Path agentWorkspace = workspace.resolve(suffix);
    try {
      Files.createDirectories(agentWorkspace);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not create M5-S01 workspace", exception);
    }

    ExecutionConfig execution = ExecutionConfig.builder()
        .timeout(Duration.ofSeconds(5))
        .maxAttempts(maxAttempts)
        .initialBackoff(Duration.ofMillis(1))
        .maxBackoff(Duration.ofMillis(2))
        .retryOn(ignored -> true)
        .build();

    HarnessAgent.Builder builder = HarnessAgent.builder()
        .name("crewscope-m5-s01-" + suffix)
        .sysPrompt("Call connection_probe once, then return the required structured result.")
        .model(model)
        .toolkit(toolkit)
        .workspace(agentWorkspace)
        .stateStore(new InMemoryAgentStateStore())
        .maxIters(4)
        .maxRetries(maxAttempts)
        .modelExecutionConfig(execution)
        .disableFilesystemTools()
        .disableShellTool()
        .disableSubagents()
        .disableMemoryTools()
        .disableMemoryHooks()
        .disableDynamicSkills()
        .disableDefaultWorkspaceSkills()
        .disableWorkspaceContext()
        .disableAtPathExpansion()
        .disableToolsConfig()
        .enableAgentTracingLog(false);
    if (fallbackModel != null) {
      builder.fallbackModel(fallbackModel);
    }

    RuntimeContext runtimeContext = RuntimeContext.builder()
        .userId("m5-s01-user-" + suffix)
        .sessionId("m5-s01-session-" + suffix)
        .build();
    try (HarnessAgent agent = builder.build()) {
      Msg result = agent.call(
              List.of(new io.agentscope.core.message.UserMessage("Run the connection probe")),
              ProbeResult.class,
              runtimeContext)
          .block(CALL_TIMEOUT);
      assertNotNull(result);
      assertEquals(1, tool.executions());
      return result.getStructuredData(ProbeResult.class);
    }
  }

  private static ProbeBuildRequest request(
      String adapterKey,
      String connectionKey,
      URI endpoint,
      String credential,
      String modelName) {
    return new ProbeBuildRequest(
        adapterKey,
        connectionKey,
        endpoint,
        credential,
        modelName,
        new ProbeGenerateOptions(0.0, 256, false));
  }

  private static void assertConnection(
      List<CapturedRequest> requests, String credential, String modelName) throws Exception {
    assertThat(requests).isNotEmpty();
    for (CapturedRequest request : requests) {
      assertEquals("Bearer " + credential, request.authorization());
      JsonNode payload = JSON.readTree(request.body());
      assertEquals(modelName, payload.path("model").asText());
    }
  }

  private static List<String> toolNames(JsonNode payload) {
    List<String> names = new java.util.ArrayList<>();
    payload.path("tools").forEach(tool -> names.add(tool.path("function").path("name").asText()));
    return List.copyOf(names);
  }

  private static String toolCall(String model, String id, String toolName) {
    return completion(model, Map.of(
        "role", "assistant",
        "content", "",
        "tool_calls", List.of(Map.of(
            "id", id,
            "type", "function",
            "function", Map.of("name", toolName, "arguments", "{}")))),
        "tool_calls");
  }

  private static String structuredToolCall(
      String model, String id, String provider, String marker) {
    String arguments = json(Map.of("response", Map.of(
        "provider", provider,
        "connectionMarker", marker)));
    return completion(model, Map.of(
        "role", "assistant",
        "content", "",
        "tool_calls", List.of(Map.of(
            "id", id,
            "type", "function",
            "function", Map.of(
                "name", "generate_response",
                "arguments", arguments)))),
        "tool_calls");
  }

  private static String nativeStructuredResponse(String model, String provider, String marker) {
    return completion(model, Map.of(
        "role", "assistant",
        "content", json(Map.of(
            "provider", provider,
            "connectionMarker", marker))),
        "stop");
  }

  private static String completion(String model, Map<String, Object> message, String finishReason) {
    return json(Map.of(
        "id", "m5-s01-" + model,
        "object", "chat.completion",
        "created", 1,
        "model", model,
        "choices", List.of(Map.of(
            "index", 0,
            "message", message,
            "finish_reason", finishReason)),
        "usage", Map.of(
            "prompt_tokens", 12,
            "completion_tokens", 8,
            "total_tokens", 20)));
  }

  private static String json(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Could not create M5-S01 JSON fixture", exception);
    }
  }

  record ProbeResult(String provider, String connectionMarker) {}

  record ProbeGenerateOptions(double temperature, int maxTokens, boolean parallelToolCalls) {}

  /**
   * Test-only representation of the trusted Adapter request frozen by M5-S01. Production M5-I03
   * replaces the credential String with the short-lived Credential Handle from M5-I02.
   */
  record ProbeBuildRequest(
      String adapterKey,
      String connectionKey,
      URI endpoint,
      String credential,
      String modelName,
      ProbeGenerateOptions generateOptions) {}

  interface ProbeModelAdapter {

    String adapterKey();

    Model create(ProbeBuildRequest request);
  }

  static final class ProbeModelFactory {

    private final Map<String, ProbeModelAdapter> adapters;

    ProbeModelFactory(List<ProbeModelAdapter> adapters) {
      Map<String, ProbeModelAdapter> indexed = new LinkedHashMap<>();
      for (ProbeModelAdapter adapter : adapters) {
        ProbeModelAdapter previous = indexed.putIfAbsent(adapter.adapterKey(), adapter);
        if (previous != null) {
          throw new IllegalStateException("Duplicate model adapter: " + adapter.adapterKey());
        }
      }
      this.adapters = Map.copyOf(indexed);
    }

    Model create(ProbeBuildRequest request) {
      ProbeModelAdapter adapter = adapters.get(request.adapterKey());
      if (adapter == null) {
        throw new IllegalArgumentException("Unknown model adapter: " + request.adapterKey());
      }
      return adapter.create(request);
    }
  }

  abstract static class OpenAiCompatibleProbeAdapter implements ProbeModelAdapter {

    @Override
    public Model create(ProbeBuildRequest request) {
      GenerateOptions options = GenerateOptions.builder()
          .temperature(request.generateOptions().temperature())
          .maxTokens(request.generateOptions().maxTokens())
          .parallelToolCalls(request.generateOptions().parallelToolCalls())
          .build();
      OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
          .apiKey(request.credential())
          .baseUrl(request.endpoint().toString())
          .modelName(request.modelName())
          .stream(false)
          .generateOptions(options);
      customize(builder);
      return builder.build();
    }

    abstract void customize(OpenAIChatModel.Builder builder);
  }

  static final class DeepSeekProbeAdapter extends OpenAiCompatibleProbeAdapter {

    @Override
    public String adapterKey() {
      return "deepseek";
    }

    @Override
    void customize(OpenAIChatModel.Builder builder) {
      builder.formatter(new DeepSeekFormatter());
      builder.nativeStructuredOutput(false);
      builder.nativeStructuredOutputWithTools(false);
    }
  }

  static final class OpenAiProbeAdapter extends OpenAiCompatibleProbeAdapter {

    @Override
    public String adapterKey() {
      return "openai";
    }

    @Override
    void customize(OpenAIChatModel.Builder builder) {
      builder.formatter(new OpenAIChatFormatter());
      builder.nativeStructuredOutput(true);
      builder.nativeStructuredOutputWithTools(true);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class ProbeAdapterConfiguration {

    @Bean
    ProbeModelAdapter deepSeekProbeAdapter() {
      return new DeepSeekProbeAdapter();
    }

    @Bean
    ProbeModelAdapter openAiProbeAdapter() {
      return new OpenAiProbeAdapter();
    }

    @Bean
    ProbeModelFactory probeModelFactory(List<ProbeModelAdapter> adapters) {
      return new ProbeModelFactory(adapters);
    }
  }

  static final class ConnectionProbeTool extends ToolBase {

    private final String marker;
    private final AtomicInteger executions = new AtomicInteger();

    ConnectionProbeTool(String marker) {
      super(ToolBase.builder()
          .name("connection_probe")
          .description("Returns the server-bound Connection marker")
          .inputSchema(Map.of("type", "object", "properties", Map.of()))
          .readOnly(true)
          .concurrencySafe(true));
      this.marker = marker;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
        Map<String, Object> toolInput, PermissionContextState context) {
      return Mono.just(PermissionDecision.allow("M5-S01 read-only fixture"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
      executions.incrementAndGet();
      return Mono.just(ToolResultBlock.text(marker)
          .withIdAndName(param.getToolUseBlock().getId(), param.getToolUseBlock().getName()));
    }

    int executions() {
      return executions.get();
    }
  }

  record CapturedRequest(String authorization, String body) {}

  record StubResponse(int status, String body) {

    static StubResponse ok(String body) {
      return new StubResponse(200, body);
    }

    static StubResponse failure(int status) {
      return new StubResponse(status, json(Map.of("error", Map.of("message", "fixture failure"))));
    }
  }

  static final class ProviderStub implements AutoCloseable {

    private final HttpServer server;
    private final IntFunction<StubResponse> responses;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();

    private ProviderStub(HttpServer server, IntFunction<StubResponse> responses) {
      this.server = server;
      this.responses = responses;
    }

    static ProviderStub start(IntFunction<StubResponse> responses) throws IOException {
      HttpServer server = HttpServer.create(
          new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      ProviderStub stub = new ProviderStub(server, responses);
      server.createContext("/v1/chat/completions", stub::handle);
      server.start();
      return stub;
    }

    URI baseUri() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    List<CapturedRequest> requests() {
      return List.copyOf(requests);
    }

    private void handle(HttpExchange exchange) throws IOException {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      requests.add(new CapturedRequest(
          exchange.getRequestHeaders().getFirst("Authorization"), body));
      StubResponse response = responses.apply(calls.incrementAndGet());
      byte[] payload = response.body().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(response.status(), payload.length);
      exchange.getResponseBody().write(payload);
      exchange.close();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
