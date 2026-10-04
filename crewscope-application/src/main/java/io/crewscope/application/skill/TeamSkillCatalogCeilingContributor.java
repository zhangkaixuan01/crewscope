package io.crewscope.application.skill;

import io.crewscope.application.agent.ApprovedSkillCeilingContributor;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.skill.TeamSkillKey;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Ceiling proof from the Team's live catalog (M10-A03b): the save boundary of the
 * Coding template admits exactly the keys whose heads are currently PUBLISHED,
 * aggregated through the same keyset listing the management surface reads. Drafts
 * and disabled skills never widen the ceiling, so an approval cannot authorize a
 * key that would not load.
 */
public final class TeamSkillCatalogCeilingContributor implements ApprovedSkillCeilingContributor {

    private static final int PAGE_SIZE = 100;

    private final TeamSkillRepository catalog;

    public TeamSkillCatalogCeilingContributor(TeamSkillRepository catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public Set<String> publishedSkillKeys(OrganizationId organizationId, TeamId teamId) {
        Set<String> keys = new HashSet<>();
        Optional<TeamSkillKey> cursor = Optional.empty();
        while (true) {
            TeamSkillPage page = catalog.findByTeam(
                    organizationId,
                    teamId,
                    TeamSkillFilter.effectivelyPublished(),
                    new TeamSkillPageRequest(cursor, PAGE_SIZE));
            page.items().forEach(skill -> keys.add(skill.skillKey().value()));
            if (page.nextSkillKey().isEmpty()) {
                return Set.copyOf(keys);
            }
            cursor = page.nextSkillKey();
        }
    }
}
