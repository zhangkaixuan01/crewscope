package io.crewscope.integration.provider.workitem;

import io.crewscope.application.provider.CapabilityProvider;
import io.crewscope.application.provider.ProviderDescriptor;
import io.crewscope.domain.provider.ProviderType;

public final class NativeWorkItemProvider implements CapabilityProvider {

    @Override
    public ProviderDescriptor descriptor() {
        return new ProviderDescriptor(
                ProviderType.WORK_ITEM, "native-work-item", "1.0.0", "CrewScope WorkItem");
    }
}
