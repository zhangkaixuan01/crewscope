package io.crewscope.integration.provider.workitem;

import io.crewscope.application.provider.CapabilityProvider;
import io.crewscope.application.provider.BuiltInProviderRegistration;
import io.crewscope.application.provider.ProviderDescriptor;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderType;

/** Product-owned connectionless WorkItem Provider backed by CrewScope PostgreSQL facts. */
public final class NativeWorkItemProvider implements CapabilityProvider {

    private static final BuiltInProviderRegistration REGISTRATION =
            new BuiltInProviderRegistration(
                    "work-item",
                    ProviderType.WORK_ITEM,
                    "1.0.0",
                    "CrewScope WorkItem",
                    "native-work-item",
                    "1.0.0",
                    ProviderCapabilities.of(
                            "workitem.read",
                            "workitem.create",
                            "workitem.update",
                            "workitem.comment",
                            "workitem.resource-link"));

    @Override
    public ProviderDescriptor descriptor() {
        return REGISTRATION.descriptor();
    }

    public BuiltInProviderRegistration registration() {
        return REGISTRATION;
    }
}
