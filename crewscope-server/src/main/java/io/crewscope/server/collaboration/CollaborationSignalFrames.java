package io.crewscope.server.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.domain.collaboration.CollaborationResourceScope;
import io.crewscope.domain.collaboration.ResourceScope;
import io.crewscope.domain.collaboration.WorkProjectScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A01 signal frame constructors (M11-A01, ADR-032). Every frame is built as an ordered map
 * and serialized at this single point, shared by the handler and the fanout, so the wire
 * shape stays in one place. The scope coordinate renders exactly the shape the handler's
 * inbound {@code parseScope} accepts — an echoed subscription scope and a signal scope are
 * byte-compatible. Frames carry coordinates, labels and versions only: no title, no
 * content, no credentials ever fit through these constructors.
 */
public final class CollaborationSignalFrames {

  private static final ObjectMapper JSON = new ObjectMapper();

  private CollaborationSignalFrames() {}

  /** presence_snapshot: who is in the scope right now, one deduplicated entry per principal. */
  public static String presenceSnapshot(
      String subscriptionId,
      CollaborationResourceScope scope,
      List<CollaborationPresenceStore.PresentPrincipal> present) {
    Map<String, Object> frame = base("presence_snapshot", subscriptionId);
    frame.put("scope", scopeJson(scope));
    List<Map<String, Object>> entries = new ArrayList<>(present.size());
    for (CollaborationPresenceStore.PresentPrincipal principal : present) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("principalId", principal.principalId());
      entry.put("displayName", principal.displayName());
      entry.put("connections", principal.connections());
      entries.add(entry);
    }
    frame.put("present", entries);
    return write(frame);
  }

  /** presence_delta: one principal entered or left, with their remaining connection count. */
  public static String presenceDelta(
      String subscriptionId,
      CollaborationResourceScope scope,
      String action,
      String principalId,
      String displayName,
      int remainingConnections) {
    Map<String, Object> frame = base("presence_delta", subscriptionId);
    frame.put("scope", scopeJson(scope));
    frame.put("action", action);
    frame.put("principalId", principalId);
    frame.put("displayName", displayName);
    frame.put("connections", remainingConnections);
    return write(frame);
  }

  /** typing: another principal in the scope started or stopped typing. */
  public static String typing(
      String subscriptionId,
      CollaborationResourceScope scope,
      String principalId,
      String displayName,
      String state) {
    Map<String, Object> frame = base("typing", subscriptionId);
    frame.put("scope", scopeJson(scope));
    frame.put("principalId", principalId);
    frame.put("displayName", displayName);
    frame.put("state", state);
    return write(frame);
  }

  /** resource_changed: the named resource moved to a new version — coordinates plus version only. */
  public static String resourceChanged(
      String subscriptionId, CollaborationResourceScope scope, long version) {
    Map<String, Object> frame = base("resource_changed", subscriptionId);
    frame.put("scope", scopeJson(scope));
    frame.put("version", version);
    return write(frame);
  }

  /** error reply for an inbound signal the caller throttled (the connection stays open). */
  public static String rateLimited() {
    Map<String, Object> frame = new LinkedHashMap<>();
    frame.put("type", "error");
    frame.put("code", "rate_limited");
    return write(frame);
  }

  /** error reply for a signal addressing a subscription handle the connection does not hold. */
  public static String unknownSubscription(String subscriptionId) {
    Map<String, Object> frame = new LinkedHashMap<>();
    frame.put("type", "error");
    frame.put("code", "unknown_subscription");
    frame.put("subscriptionId", subscriptionId);
    return write(frame);
  }

  /**
   * Renders the scope coordinate in the exact inbound shape: organization and team always,
   * resourceType/resourceId for the resource and project granularities (the ADR-032
   * wire names, matching the Redis keyspace segments).
   */
  public static Map<String, Object> scopeJson(CollaborationResourceScope scope) {
    Objects.requireNonNull(scope, "scope");
    Map<String, Object> coordinate = new LinkedHashMap<>();
    coordinate.put("organization", scope.organizationId().value().toString());
    coordinate.put("team", scope.teamId().value().toString());
    if (scope instanceof WorkProjectScope project) {
      coordinate.put("resourceType", "work_project");
      coordinate.put("resourceId", project.projectId().value().toString());
    } else if (scope instanceof ResourceScope resource) {
      coordinate.put("resourceType", resource.type().name().toLowerCase(Locale.ROOT));
      coordinate.put("resourceId", resource.resourceId().toString());
    }
    // TeamScope: organization and team only.
    return coordinate;
  }

  private static Map<String, Object> base(String type, String subscriptionId) {
    Map<String, Object> frame = new LinkedHashMap<>();
    frame.put("type", type);
    frame.put("subscriptionId", Objects.requireNonNull(subscriptionId, "subscriptionId"));
    return frame;
  }

  private static String write(Map<String, Object> frame) {
    try {
      return JSON.writeValueAsString(frame);
    } catch (Exception exception) {
      // Plain maps of strings and integers: the mapper cannot fail on this shape.
      throw new IllegalStateException("failed to serialize collaboration signal frame", exception);
    }
  }
}
