package io.crewscope.domain.model;

import io.crewscope.domain.agent.ResolvedModelRole;
import io.crewscope.domain.agent.ResolvedModelSelection;
import java.util.Objects;

/**
 * Attribution coordinates of one resolved chat Model instance, carried onto usage facts
 * (M10-F03). The usage ledger records the provider/model/connection coordinates plus the
 * role the selection played, so a CHAT_PRIMARY fact stays distinguishable from a
 * CHAT_FALLBACK fact even when both resolve the same provider model.
 */
public record ModelCallAttribution(
        ModelProviderKey providerKey,
        ModelId modelId,
        ModelConnectionId connectionId,
        long connectionVersion,
        ModelUsageRole role) {

    public ModelCallAttribution {
        providerKey = Objects.requireNonNull(providerKey, "providerKey");
        modelId = Objects.requireNonNull(modelId, "modelId");
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
        role = Objects.requireNonNull(role, "role");
        if (connectionVersion < 0) {
            throw new IllegalArgumentException("connectionVersion must be non-negative");
        }
    }

    /** Maps one pinned selection onto the usage-ledger role vocabulary. */
    public static ModelCallAttribution fromSelection(
            ResolvedModelSelection selection, ModelId modelId) {
        ResolvedModelSelection pinned = Objects.requireNonNull(selection, "selection");
        return chat(
                pinned.providerKey(),
                modelId,
                pinned.connectionId(),
                pinned.connectionVersion(),
                pinned.role());
    }

    /** Resolved-role vocabulary mapping: PRIMARY/FALLBACK become CHAT_PRIMARY/CHAT_FALLBACK. */
    public static ModelCallAttribution chat(
            ModelProviderKey providerKey,
            ModelId modelId,
            ModelConnectionId connectionId,
            long connectionVersion,
            ResolvedModelRole role) {
        return new ModelCallAttribution(
                providerKey,
                modelId,
                connectionId,
                connectionVersion,
                switch (Objects.requireNonNull(role, "role")) {
                    case PRIMARY -> ModelUsageRole.CHAT_PRIMARY;
                    case FALLBACK -> ModelUsageRole.CHAT_FALLBACK;
                });
    }
}
