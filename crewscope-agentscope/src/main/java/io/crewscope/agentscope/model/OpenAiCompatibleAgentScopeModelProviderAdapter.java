package io.crewscope.agentscope.model;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.DeepSeekFormatter;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import io.crewscope.application.model.ProviderCredentialHandle;
import io.crewscope.domain.model.ModelAdapterKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/** Adapter for DeepSeek and explicitly catalogued OpenAI-compatible HTTP providers. */
public final class OpenAiCompatibleAgentScopeModelProviderAdapter
        implements AgentScopeModelProviderAdapter {

    public static final ModelAdapterKey KEY = new ModelAdapterKey("openai-compatible");
    public static final String VERSION = "2.0.0-m5-v1";

    private final Duration timeout;
    private final Duration initialBackoff;
    private final Duration maximumBackoff;

    public OpenAiCompatibleAgentScopeModelProviderAdapter(
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
        requirePolicy(request);
        ProviderCredentialHandle handle = requireCredentialCoordinate(request, credentialHandle);
        return handle
                .useSecret(secret -> buildWithCredential(
                        request, new String(secret, StandardCharsets.UTF_8)));
    }

    private Model buildWithCredential(TrustedModelBuildRequest request, String credential) {
        GenerateOptions safeOptions = SafeAgentScopeGenerateOptionsMapper.map(
                request.generateOptions(), timeout, initialBackoff, maximumBackoff);
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(requireCredential(credential))
                .baseUrl(request.endpoint().value())
                .endpointPath(request.endpointPath())
                .modelName(request.modelName())
                .stream(true)
                .generateOptions(safeOptions);
        if (request.formatterPolicy() == AgentScopeFormatterPolicy.DEEPSEEK) {
            builder.formatter(new DeepSeekFormatter());
        } else {
            builder.formatter(new OpenAIChatFormatter());
        }
        boolean nativeOutput = request.structuredOutputCompatibility()
                == StructuredOutputCompatibility.NATIVE;
        builder.nativeStructuredOutput(nativeOutput);
        builder.nativeStructuredOutputWithTools(nativeOutput);
        return builder.build();
    }

    private static void requirePolicy(TrustedModelBuildRequest request) {
        if (request.formatterPolicy() == AgentScopeFormatterPolicy.DEEPSEEK
                && request.structuredOutputCompatibility()
                        != StructuredOutputCompatibility.SYNTHETIC_TOOL) {
            throw new AgentScopeModelBuildException(
                    AgentScopeModelBuildException.Code.UNSUPPORTED_FORMATTER_POLICY);
        }
    }

    static String requireCredential(String credential) {
        if (credential == null || credential.isBlank()) {
            throw new AgentScopeModelBuildException(
                    AgentScopeModelBuildException.Code.CREDENTIAL_UNAVAILABLE);
        }
        return credential;
    }

    static ProviderCredentialHandle requireCredentialCoordinate(
            TrustedModelBuildRequest request, ProviderCredentialHandle credentialHandle) {
        TrustedModelBuildRequest trusted = Objects.requireNonNull(request, "request");
        ProviderCredentialHandle handle = Objects.requireNonNull(
                credentialHandle, "credentialHandle");
        if (!trusted.connectionId().equals(handle.connectionId())
                || !trusted.credentialVersion().equals(handle.credentialVersion())) {
            handle.close();
            throw new AgentScopeModelBuildException(
                    AgentScopeModelBuildException.Code.CREDENTIAL_COORDINATE_MISMATCH);
        }
        return handle;
    }
}
