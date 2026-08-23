package io.crewscope.integration.provider.sourcecode;

import io.crewscope.application.provider.CapabilityProvider;
import io.crewscope.application.provider.ProviderDescriptor;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderConnectionRequirement;
import io.crewscope.domain.provider.ProviderType;
import java.util.Optional;

/** GitHub source-code Provider descriptor and its fixed connection contract. */
public final class GitHubSourceCodeProvider implements CapabilityProvider {

    public static final String CONNECTOR_KEY = "github-source-code";

    private static final ProviderCapabilities CAPABILITIES = ProviderCapabilities.of(
            "source.repository.catalog",
            "source.repository.read",
            "source.repository.push",
            "source.pull-request.create");

    @Override
    public ProviderDescriptor descriptor() {
        return new ProviderDescriptor(
                ProviderType.SOURCE_CODE, CONNECTOR_KEY, "1.0.0", "GitHub");
    }

    public ProviderCapabilities capabilities() {
        return CAPABILITIES;
    }

    public ProviderConnectionRequirement connectionRequirement() {
        return ProviderConnectionRequirement.REQUIRED;
    }

    public Optional<String> connectorKey() {
        return Optional.of(CONNECTOR_KEY);
    }
}
