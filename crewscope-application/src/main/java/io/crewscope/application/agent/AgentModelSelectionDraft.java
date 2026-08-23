package io.crewscope.application.agent;

import io.crewscope.domain.model.ModelCatalogEntryId;
import io.crewscope.domain.model.ModelCatalogRevision;
import io.crewscope.domain.model.ModelConnectionId;
import java.util.Objects;

/** Client-writable selection coordinate containing stable IDs only. */
public record AgentModelSelectionDraft(
        ModelConnectionId connectionId,
        ModelCatalogEntryId catalogEntryId,
        ModelCatalogRevision catalogRevision) {

    public AgentModelSelectionDraft {
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
        catalogEntryId = Objects.requireNonNull(catalogEntryId, "catalogEntryId");
        catalogRevision = Objects.requireNonNull(catalogRevision, "catalogRevision");
    }
}
