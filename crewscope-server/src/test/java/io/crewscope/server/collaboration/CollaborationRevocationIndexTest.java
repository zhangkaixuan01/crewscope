package io.crewscope.server.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Contract of the D02 member-to-connections reverse index: one member's tabs in one Team
 * accumulate under one key, keys are isolated per member and per Team, and forgetting a
 * connection leaves no residue for a later sweep of the same member.
 */
class CollaborationRevocationIndexTest {

  private final OrganizationId organizationId = OrganizationId.generate();
  private final TeamId teamId = TeamId.generate();
  private final TeamId otherTeamId = TeamId.generate();

  @Test
  void oneMemberAccumulatesTabsUnderOneKey() {
    UUID member = UUID.randomUUID();
    CollaborationRevocationIndex index = new CollaborationRevocationIndex();
    index.record(member, organizationId, teamId, "conn-a");
    index.record(member, organizationId, teamId, "conn-b");

    assertEquals(Set.of("conn-a", "conn-b"), index.connectionsOf(member, organizationId, teamId));
  }

  @Test
  void keysAreIsolatedPerMemberAndPerTeam() {
    UUID member = UUID.randomUUID();
    UUID otherMember = UUID.randomUUID();
    CollaborationRevocationIndex index = new CollaborationRevocationIndex();
    index.record(member, organizationId, teamId, "conn-member-team");
    index.record(member, organizationId, otherTeamId, "conn-member-other");
    index.record(otherMember, organizationId, teamId, "conn-other-member");

    assertEquals(Set.of("conn-member-team"), index.connectionsOf(member, organizationId, teamId));
    assertEquals(
        Set.of("conn-member-other"), index.connectionsOf(member, organizationId, otherTeamId));
    assertTrue(index.connectionsOf(UUID.randomUUID(), organizationId, teamId).isEmpty(),
        "an unknown member has nothing to sweep");
  }

  @Test
  void forgettingAConnectionLeavesNoResidueAnywhere() {
    UUID member = UUID.randomUUID();
    CollaborationRevocationIndex index = new CollaborationRevocationIndex();
    index.record(member, organizationId, teamId, "conn-a");
    index.record(member, organizationId, otherTeamId, "conn-a");
    index.record(member, organizationId, teamId, "conn-b");

    index.forget("conn-a");
    index.forget("conn-a");

    assertEquals(Set.of("conn-b"), index.connectionsOf(member, organizationId, teamId));
    assertTrue(index.connectionsOf(member, organizationId, otherTeamId).isEmpty(),
        "the emptied bucket itself is gone");
    index.forget("conn-never-recorded");
  }
}
