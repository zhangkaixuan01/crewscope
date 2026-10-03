package io.crewscope.application.knowledge;

import io.crewscope.domain.knowledge.distiller.KnowledgeDistillerInitialization;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Optional;

/** Atomic persistence Port for each Team's unique built-in Distiller Principal/Profile pair. */
public interface KnowledgeDistillerRepository {

    /**
     * Inserts the deterministic candidate when absent and otherwise returns the existing pair.
     * Implementations serialize by Organization and Team and never commit a partial pair.
     */
    KnowledgeDistillerInitialization initializeIfAbsent(KnowledgeDistillerInitialization candidate);

    /**
     * Resolves the Team's Distiller pair. Named distinctly from the Observer Port so one
     * JPA adapter can implement both without a return-type-only signature clash.
     */
    Optional<KnowledgeDistillerInitialization> findDistillerForTeam(
            OrganizationId organizationId, TeamId teamId);

    /** Commits synchronized Principal/Profile lifecycle changes atomically with strong versions. */
    KnowledgeDistillerInitialization updateLifecycle(KnowledgeDistillerInitialization initialization);
}
