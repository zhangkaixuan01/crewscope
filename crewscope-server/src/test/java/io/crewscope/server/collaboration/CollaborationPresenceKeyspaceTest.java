package io.crewscope.server.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.crewscope.domain.collaboration.CollaborationResourceType;
import io.crewscope.domain.collaboration.ResourceScope;
import io.crewscope.domain.collaboration.TeamScope;
import io.crewscope.domain.collaboration.WorkProjectScope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Environment-isolated key rendering for the three scope granularities. */
class CollaborationPresenceKeyspaceTest {

  private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID TEAM = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

  @Test
  void environmentMustBeLowercaseLettersDigitsOrHyphens() {
    assertThatThrownBy(() -> new CollaborationPresenceKeyspace("Bad Env"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CollaborationPresenceKeyspace(" "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CollaborationPresenceKeyspace(null))
        .isInstanceOf(NullPointerException.class);
    new CollaborationPresenceKeyspace("team-beta");
    new CollaborationPresenceKeyspace("dev");
  }

  @Test
  void connectionKeyCarriesTheEnvironmentAndVersion() {
    CollaborationPresenceKeyspace keyspace = new CollaborationPresenceKeyspace("team-beta");

    assertThat(keyspace.connectionKey("conn-1"))
        .isEqualTo("crewscope:team-beta:collaboration:v1:presence:conn:conn-1");
  }

  @Test
  void teamScopeRendersUnderTheTeamSegments() {
    CollaborationPresenceKeyspace keyspace = new CollaborationPresenceKeyspace("dev");
    TeamScope scope = new TeamScope(new OrganizationId(ORG), new TeamId(TEAM));

    assertThat(keyspace.scopeKey(scope))
        .isEqualTo("crewscope:dev:collaboration:v1:presence:scope:"
            + ORG + ":" + TEAM + ":team:all");
    assertThat(keyspace.resourceTypeSegment(scope)).isEqualTo("team");
    assertThat(keyspace.resourceIdSegment(scope)).isEqualTo("all");
  }

  @Test
  void workProjectScopeRendersUnderTheProjectId() {
    CollaborationPresenceKeyspace keyspace = new CollaborationPresenceKeyspace("dev");
    UUID projectId = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    WorkProjectScope scope =
        new WorkProjectScope(
            new OrganizationId(ORG), new TeamId(TEAM), new WorkProjectId(projectId));

    assertThat(keyspace.scopeKey(scope))
        .isEqualTo("crewscope:dev:collaboration:v1:presence:scope:"
            + ORG + ":" + TEAM + ":work_project:" + projectId);
  }

  @Test
  void resourceScopeRendersUnderLowercaseTypeAndResourceId() {
    CollaborationPresenceKeyspace keyspace = new CollaborationPresenceKeyspace("dev");
    UUID itemId = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
    ResourceScope scope =
        new ResourceScope(
            new OrganizationId(ORG), new TeamId(TEAM), CollaborationResourceType.WORK_ITEM, itemId);

    assertThat(keyspace.scopeKey(scope))
        .isEqualTo("crewscope:dev:collaboration:v1:presence:scope:"
            + ORG + ":" + TEAM + ":work_item:" + itemId);
    assertThat(keyspace.resourceTypeSegment(scope)).isEqualTo("work_item");
    assertThat(keyspace.resourceIdSegment(scope)).isEqualTo(itemId.toString());
  }

  @Test
  void scopePatternCoversEveryScopeKeyButNothingElse() {
    CollaborationPresenceKeyspace keyspace = new CollaborationPresenceKeyspace("dev");

    assertThat(keyspace.scopePattern())
        .isEqualTo("crewscope:dev:collaboration:v1:presence:scope:*");
  }

  @Test
  void parseScopeKeyInvertsEveryGranularity() {
    CollaborationPresenceKeyspace keyspace = new CollaborationPresenceKeyspace("dev");
    OrganizationId organizationId = new OrganizationId(ORG);
    TeamId teamId = new TeamId(TEAM);
    UUID projectId = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    UUID itemId = UUID.fromString("00000000-0000-0000-0000-0000000000d4");

    assertThat(keyspace.parseScopeKey(
            keyspace.scopeKey(new TeamScope(organizationId, teamId))))
        .contains(new TeamScope(organizationId, teamId));
    assertThat(keyspace.parseScopeKey(keyspace.scopeKey(
            new WorkProjectScope(organizationId, teamId, new WorkProjectId(projectId)))))
        .contains(new WorkProjectScope(organizationId, teamId, new WorkProjectId(projectId)));
    assertThat(keyspace.parseScopeKey(keyspace.scopeKey(new ResourceScope(
            organizationId, teamId, CollaborationResourceType.CONVERSATION, itemId))))
        .contains(new ResourceScope(
            organizationId, teamId, CollaborationResourceType.CONVERSATION, itemId));
  }

  @Test
  void parseScopeKeyRejectsForeignAndMalformedKeys() {
    CollaborationPresenceKeyspace dev = new CollaborationPresenceKeyspace("dev");
    CollaborationPresenceKeyspace prod = new CollaborationPresenceKeyspace("prod");

    assertThat(dev.parseScopeKey(prod.scopeKey(new TeamScope(
            new OrganizationId(ORG), new TeamId(TEAM)))))
        .isEmpty();
    assertThat(dev.parseScopeKey("crewscope:dev:collaboration:v1:presence:scope:only-two"))
        .isEmpty();
    assertThat(dev.parseScopeKey(
            "crewscope:dev:collaboration:v1:presence:scope:" + ORG + ":" + TEAM
                + ":team:not-the-sentinel"))
        .isEmpty();
    assertThat(dev.parseScopeKey(
            "crewscope:dev:collaboration:v1:presence:scope:" + ORG + ":" + TEAM
                + ":work_item:not-a-uuid"))
        .isEmpty();
    assertThat(dev.parseScopeKey(
            "crewscope:dev:collaboration:v1:presence:scope:" + ORG + ":" + TEAM
                + ":unknown_type:" + ORG))
        .isEmpty();
  }
}
