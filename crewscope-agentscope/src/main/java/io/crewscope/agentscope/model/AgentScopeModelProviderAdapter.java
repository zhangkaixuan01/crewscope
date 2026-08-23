package io.crewscope.agentscope.model;

import io.agentscope.core.model.Model;
import io.crewscope.application.model.ProviderCredentialHandle;
import io.crewscope.domain.model.ModelAdapterKey;

/** Trusted SPI for one AgentScope provider protocol implementation. */
public interface AgentScopeModelProviderAdapter {

    ModelAdapterKey adapterKey();

    String adapterVersion();

    Model build(TrustedModelBuildRequest request, ProviderCredentialHandle credentialHandle);
}
