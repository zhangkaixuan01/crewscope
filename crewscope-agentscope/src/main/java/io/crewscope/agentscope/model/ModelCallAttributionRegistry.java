package io.crewscope.agentscope.model;

import io.agentscope.core.model.Model;
import io.crewscope.domain.model.ModelCallAttribution;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Call-scoped Model-instance → attribution map (M10-F03). The shared AgentScope model cache
 * hands one Model instance to unrelated executions and its key carries no role or Team, so
 * attribution can never be a factory-global table: each execution registers the exact pair
 * it pinned, and the telemetry middleware resolves the instance that served a call.
 * Keys are compared by reference — Model implementations are not required to be value
 * objects, and the shared cache may return equal-valued but distinct instances. A pair
 * with a fallback is never resolved by reference at all: ReActAgent wraps it in a
 * per-call fallback switcher (a fresh instance every call), so resolution falls back to
 * the model name, which the switcher delegates to the currently active instance.
 */
public final class ModelCallAttributionRegistry {

    private final ConcurrentMap<Model, ModelCallAttribution> attributions =
            new ConcurrentHashMap<>();

    public ModelCallAttributionRegistry register(Model model, ModelCallAttribution attribution) {
        attributions.put(
                Objects.requireNonNull(model, "model"),
                Objects.requireNonNull(attribution, "attribution"));
        return this;
    }

    /**
     * Registers every attributed instance of one resolved pair. Re-registering a pair whose
     * instances the model cache reused (or rebuilt with new coordinates) overwrites by
     * reference, so a mid-call rebuild such as the structured-output recovery agent stays
     * attributed.
     */
    public ModelCallAttributionRegistry registerAll(ResolvedAgentScopeModels models) {
        ResolvedAgentScopeModels pair = Objects.requireNonNull(models, "models");
        pair.primaryAttribution()
                .ifPresent(attribution -> register(pair.primary(), attribution));
        pair.fallback().ifPresent(model -> pair.fallbackAttribution()
                .ifPresent(attribution -> register(model, attribution)));
        return this;
    }

    public Optional<ModelCallAttribution> resolve(Model model) {
        if (model == null) {
            return Optional.empty();
        }
        ModelCallAttribution exact = attributions.get(model);
        if (exact != null) {
            return Optional.of(exact);
        }
        return resolveByName(model);
    }

    /**
     * Resolves through {@link Model#getModelName()} for the per-call fallback switcher the
     * SDK builds around a pinned pair: its name is the currently active instance's name, so
     * this both identifies the pinned model and follows a mid-call fallback switch. A name
     * that maps to two distinct attributions resolves to nothing rather than guessing.
     */
    private Optional<ModelCallAttribution> resolveByName(Model model) {
        String name = model.getModelName();
        if (name == null) {
            return Optional.empty();
        }
        ModelCallAttribution matched = null;
        for (Map.Entry<Model, ModelCallAttribution> entry : attributions.entrySet()) {
            if (!name.equals(entry.getKey().getModelName())) {
                continue;
            }
            if (matched != null && !matched.equals(entry.getValue())) {
                return Optional.empty();
            }
            matched = entry.getValue();
        }
        return Optional.ofNullable(matched);
    }
}
