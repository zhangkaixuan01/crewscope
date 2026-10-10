package io.crewscope.server.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.crewscope.domain.collaboration.CollaborationResourceType;
import io.crewscope.domain.collaboration.ResourceScope;
import io.crewscope.domain.collaboration.TeamScope;
import io.crewscope.domain.collaboration.WorkProjectScope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Golden shapes of the A01 signal frames — the wire contract lives in these strings. */
class CollaborationSignalFramesTest {

  private static final String ORG = "11111111-1111-1111-1111-111111111111";
  private static final String TEAM = "22222222-2222-2222-2222-222222222222";
  private static final String ITEM = "33333333-3333-3333-3333-333333333333";
  private static final String PROJECT = "44444444-4444-4444-4444-444444444444";

  private final OrganizationId organizationId = OrganizationId.from(ORG);
  private final TeamId teamId = TeamId.from(TEAM);

  @Test
  void resourceChangedCarriesCoordinatesAndVersionOnly() {
    String frame = CollaborationSignalFrames.resourceChanged(
        "handle-1", workItemScope(), 12);

    assertEquals(
        "{\"type\":\"resource_changed\",\"subscriptionId\":\"handle-1\","
            + "\"scope\":{\"organization\":\"" + ORG + "\",\"team\":\"" + TEAM + "\","
            + "\"resourceType\":\"work_item\",\"resourceId\":\"" + ITEM + "\"},"
            + "\"version\":12}",
        frame);
  }

  @Test
  void presenceDeltaCarriesTheRemainingConnectionCount() {
    String frame = CollaborationSignalFrames.presenceDelta(
        "handle-2", teamScope(), "leave", "account:abc", "Alice", 0);

    assertEquals(
        "{\"type\":\"presence_delta\",\"subscriptionId\":\"handle-2\","
            + "\"scope\":{\"organization\":\"" + ORG + "\",\"team\":\"" + TEAM + "\"},"
            + "\"action\":\"leave\",\"principalId\":\"account:abc\",\"displayName\":\"Alice\","
            + "\"connections\":0}",
        frame);
  }

  @Test
  void typingCarriesTheState() {
    String frame = CollaborationSignalFrames.typing(
        "handle-3", workItemScope(), "account:abc", "Alice", "started");

    assertEquals(
        "{\"type\":\"typing\",\"subscriptionId\":\"handle-3\","
            + "\"scope\":{\"organization\":\"" + ORG + "\",\"team\":\"" + TEAM + "\","
            + "\"resourceType\":\"work_item\",\"resourceId\":\"" + ITEM + "\"},"
            + "\"principalId\":\"account:abc\",\"displayName\":\"Alice\",\"state\":\"started\"}",
        frame);
  }

  @Test
  void presenceSnapshotListsOneEntryPerPrincipal() {
    List<CollaborationPresenceStore.PresentPrincipal> present = List.of(
        new CollaborationPresenceStore.PresentPrincipal("account:a", "Alice", TEAM, "work_item", ITEM, 2),
        new CollaborationPresenceStore.PresentPrincipal("account:b", "", TEAM, "work_item", ITEM, 1));

    String frame = CollaborationSignalFrames.presenceSnapshot(
        "handle-4", workItemScope(), present);

    assertEquals(
        "{\"type\":\"presence_snapshot\",\"subscriptionId\":\"handle-4\","
            + "\"scope\":{\"organization\":\"" + ORG + "\",\"team\":\"" + TEAM + "\","
            + "\"resourceType\":\"work_item\",\"resourceId\":\"" + ITEM + "\"},"
            + "\"present\":["
            + "{\"principalId\":\"account:a\",\"displayName\":\"Alice\",\"connections\":2},"
            + "{\"principalId\":\"account:b\",\"displayName\":\"\",\"connections\":1}]}",
        frame);
  }

  @Test
  void errorFramesStayMinimal() {
    assertEquals(
        "{\"type\":\"error\",\"code\":\"rate_limited\"}",
        CollaborationSignalFrames.rateLimited());
    assertEquals(
        "{\"type\":\"error\",\"code\":\"unknown_subscription\",\"subscriptionId\":\"handle-5\"}",
        CollaborationSignalFrames.unknownSubscription("handle-5"));
  }

  @Test
  void scopeJsonRendersEveryGranularityInTheInboundShape() {
    assertEquals(
        Map.of("organization", ORG, "team", TEAM),
        CollaborationSignalFrames.scopeJson(teamScope()));

    assertEquals(
        Map.of("organization", ORG, "team", TEAM,
            "resourceType", "conversation", "resourceId", ITEM),
        CollaborationSignalFrames.scopeJson(
            new ResourceScope(organizationId, teamId,
                CollaborationResourceType.CONVERSATION, UUID.fromString(ITEM))));

    assertEquals(
        Map.of("organization", ORG, "team", TEAM,
            "resourceType", "work_project", "resourceId", PROJECT),
        CollaborationSignalFrames.scopeJson(
            new WorkProjectScope(organizationId, teamId,
                WorkProjectId.from(PROJECT))));
  }

  private TeamScope teamScope() {
    return new TeamScope(organizationId, teamId);
  }

  private ResourceScope workItemScope() {
    return new ResourceScope(
        organizationId, teamId, CollaborationResourceType.WORK_ITEM, UUID.fromString(ITEM));
  }
}
