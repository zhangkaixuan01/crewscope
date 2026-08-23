package io.crewscope.application.model;

import io.crewscope.domain.agent.AgentModelSelection;
import io.crewscope.domain.model.ModelCapability;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.model.ModelTokenPrice;
import java.util.Objects;
import java.util.Set;

/** Non-secret product projection for one selectable exact model/connection coordinate. */
public record SelectableModelOption(
        AgentModelSelection selection,
        String providerDisplayName,
        String modelDisplayName,
        ModelConnectionOwner connectionOwner,
        ModelRegion region,
        long contextWindowTokens,
        long maximumOutputTokens,
        Set<ModelCapability> capabilities,
        ModelTokenPrice tokenPrice) {

    public SelectableModelOption {
        selection = Objects.requireNonNull(selection, "selection");
        providerDisplayName = requireText(providerDisplayName, "providerDisplayName");
        modelDisplayName = requireText(modelDisplayName, "modelDisplayName");
        connectionOwner = Objects.requireNonNull(connectionOwner, "connectionOwner");
        region = Objects.requireNonNull(region, "region");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        tokenPrice = Objects.requireNonNull(tokenPrice, "tokenPrice");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
