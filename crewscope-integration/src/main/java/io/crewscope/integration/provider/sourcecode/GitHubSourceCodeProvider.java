package io.crewscope.integration.provider.sourcecode;

import io.crewscope.application.provider.CapabilityProvider;
import io.crewscope.application.provider.ProviderDescriptor;
import io.crewscope.domain.provider.ProviderType;

public final class GitHubSourceCodeProvider implements CapabilityProvider {

    @Override
    public ProviderDescriptor descriptor() {
        return new ProviderDescriptor(
                ProviderType.SOURCE_CODE, "github-source-code", "1.0.0", "GitHub");
    }
}
