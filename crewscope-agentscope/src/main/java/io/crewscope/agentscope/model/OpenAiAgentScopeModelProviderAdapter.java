package io.crewscope.agentscope.model;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import io.crewscope.application.model.ProviderCredentialHandle;
import io.crewscope.domain.model.ModelAdapterKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/** Conservative fallback adapter for the native OpenAI chat-completions contract. */
public final class OpenAiAgentScopeModelProviderAdapter implements AgentScopeModelProviderAdapter {

    public static final ModelAdapterKey KEY = new ModelAdapterKey("openai");
    public static final String VERSION = "2.0.0-m5-v1";

    private final Duration timeout;
    private final Duration initialBackoff;
    private final Duration maximumBackoff;

    public OpenAiAgentScopeModelProviderAdapter(
            Duration timeout, Duration initialBackoff, Duration maximumBackoff) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.initialBackoff = Objects.requireNonNull(initialBackoff, "initialBackoff");
        this.maximumBackoff = Objects.requireNonNull(maximumBackoff, "maximumBackoff");
    }

    @Override
    public ModelAdapterKey adapterKey() {
        return KEY;
    }

    @Override
    public String adapterVersion() {
        return VERSION;
    }

    @Override
    public Model build(
            TrustedModelBuildRequest request, ProviderCredentialHandle credentialHandle) {
        if (request.formatterPolicy() != AgentScopeFormatterPolicy.OPENAI
                || request.structuredOutputCompatibility()
                        != StructuredOutputCompatibility.NATIVE) {
            throw new AgentScopeModelBuildException(
                    AgentScopeModelBuildException.Code.UNSUPPORTED_FORMATTER_POLICY);
        }
        ProviderCredentialHandle handle =
                OpenAiCompatibleAgentScopeModelProviderAdapter.requireCredentialCoordinate(
                        request, credentialHandle);
        return handle
                .useSecret(secret -> buildWithCredential(
                        request, new String(secret, StandardCharsets.UTF_8)));
    }

    private Model buildWithCredential(TrustedModelBuildRequest request, String credential) {
        GenerateOptions safeOptions = SafeAgentScopeGenerateOptionsMapper.map(
                request.generateOptions(), timeout, initialBackoff, maximumBackoff);
        Model delegate = OpenAIChatModel.builder()
                .apiKey(OpenAiCompatibleAgentScopeModelProviderAdapter.requireCredential(credential))
                .baseUrl(request.endpoint().value())
                .endpointPath(request.endpointPath())
                .modelName(request.modelName())
                .stream(true)
                .formatter(new OpenAIChatFormatter())
                .generateOptions(safeOptions)
                .nativeStructuredOutput(true)
                .nativeStructuredOutputWithTools(true)
                .build();
        return delegate;
    }
}
