package io.crewscope.integration.provider.collaboration;

import io.crewscope.application.provider.CapabilityProvider;
import io.crewscope.application.provider.ProviderDescriptor;
import io.crewscope.domain.provider.ProviderType;

public final class LarkCollaborationProvider implements CapabilityProvider {

    @Override
    public ProviderDescriptor descriptor() {
        return new ProviderDescriptor(
                ProviderType.COLLABORATION, "lark-collaboration", "1.0.0", "Lark");
    }
}
