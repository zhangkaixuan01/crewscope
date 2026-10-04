package io.crewscope.application.skill;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.skill.distiller.SkillDistillerInitialization;
import java.util.Optional;

/** Atomic persistence Port for each Team's unique built-in Skill Distiller pair. */
public interface SkillDistillerRepository {

    /**
     * Inserts the deterministic candidate when absent and otherwise returns the existing
     * pair. Implementations serialize by Organization and Team and never commit a
     * partial pair.
     */
    SkillDistillerInitialization initializeIfAbsent(SkillDistillerInitialization candidate);

    /**
     * Resolves the Team's Distiller pair. Named distinctly from the Observer and Knowledge
     * Ports so one JPA adapter can implement several without a return-type-only clash.
     */
    Optional<SkillDistillerInitialization> findSkillDistillerForTeam(
            OrganizationId organizationId, TeamId teamId);

    /** Commits synchronized Principal/Profile lifecycle changes atomically with strong versions. */
    SkillDistillerInitialization updateLifecycle(SkillDistillerInitialization initialization);
}
