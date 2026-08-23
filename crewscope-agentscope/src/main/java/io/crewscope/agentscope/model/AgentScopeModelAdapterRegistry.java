package io.crewscope.agentscope.model;

import io.crewscope.domain.model.ModelAdapterKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable fail-fast registry of trusted dynamic model adapters. */
public final class AgentScopeModelAdapterRegistry {

    private final Map<ModelAdapterKey, AgentScopeModelProviderAdapter> adapters;

    public AgentScopeModelAdapterRegistry(List<AgentScopeModelProviderAdapter> adapters) {
        Objects.requireNonNull(adapters, "adapters");
        Map<ModelAdapterKey, AgentScopeModelProviderAdapter> indexed = new LinkedHashMap<>();
        for (AgentScopeModelProviderAdapter adapter : adapters) {
            AgentScopeModelProviderAdapter required = Objects.requireNonNull(adapter, "adapter");
            ModelAdapterKey key = Objects.requireNonNull(required.adapterKey(), "adapterKey");
            if (required.adapterVersion() == null || required.adapterVersion().isBlank()) {
                throw new IllegalStateException("Model adapter version must not be blank");
            }
            if (indexed.putIfAbsent(key, required) != null) {
                throw new IllegalStateException("Duplicate model adapter key: " + key);
            }
        }
        if (indexed.isEmpty()) {
            throw new IllegalStateException("At least one trusted model adapter is required");
        }
        this.adapters = Map.copyOf(indexed);
    }

    public AgentScopeModelProviderAdapter require(ModelAdapterKey key) {
        AgentScopeModelProviderAdapter adapter = adapters.get(Objects.requireNonNull(key, "key"));
        if (adapter == null) {
            throw new AgentScopeModelBuildException(
                    AgentScopeModelBuildException.Code.UNKNOWN_ADAPTER);
        }
        return adapter;
    }
}
