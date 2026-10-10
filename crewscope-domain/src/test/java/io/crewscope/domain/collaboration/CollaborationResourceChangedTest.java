package io.crewscope.domain.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Constructor invariants of the A01 change-notification fact. */
class CollaborationResourceChangedTest {

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();

    @Test
    void buildsAFactWithTheThreeLevelAudience() {
        ResourceScope item = workItem();
        Set<CollaborationResourceScope> audience = new LinkedHashSet<>();
        audience.add(item);
        audience.add(project());
        audience.add(new TeamScope(organizationId, teamId));

        CollaborationResourceChanged changed = new CollaborationResourceChanged(item, 7L, audience);

        assertEquals(item, changed.resource());
        assertEquals(7L, changed.version());
        assertEquals(3, changed.audience().size());
        assertTrue(changed.audience().contains(new TeamScope(organizationId, teamId)));
    }

    @Test
    void rejectsATeamScopeAsTheChangedResource() {
        TeamScope team = new TeamScope(organizationId, teamId);

        assertThrows(IllegalArgumentException.class,
                () -> new CollaborationResourceChanged(team, 1L, Set.of(team)));
    }

    @Test
    void rejectsANegativeVersion() {
        ResourceScope item = workItem();

        assertThrows(IllegalArgumentException.class,
                () -> new CollaborationResourceChanged(item, -1L, Set.of(item)));
    }

    @Test
    void rejectsAnAudienceWithoutTheChangedResource() {
        ResourceScope item = workItem();

        assertThrows(IllegalArgumentException.class,
                () -> new CollaborationResourceChanged(item, 1L, Set.of(project())));
        assertThrows(IllegalArgumentException.class,
                () -> new CollaborationResourceChanged(item, 1L, Set.of()));
    }

    private ResourceScope workItem() {
        return new ResourceScope(
                organizationId, teamId, CollaborationResourceType.WORK_ITEM,
                java.util.UUID.randomUUID());
    }

    private WorkProjectScope project() {
        return new WorkProjectScope(organizationId, teamId, WorkProjectId.generate());
    }
}
