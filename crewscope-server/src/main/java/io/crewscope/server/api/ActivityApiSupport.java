package io.crewscope.server.api;

import io.crewscope.application.activity.ActivityFilter;
import io.crewscope.domain.activity.ActivityCategory;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** Strict route and normalized multi-value filter parsing shared by Activity controllers. */
final class ActivityApiSupport {

  static final String PROJECTION_NAME = "team-activity";
  private static final int MAX_FILTER_VALUES = 20;

  private ActivityApiSupport() {}

  static TeamRoute teamRoute(String organizationId, String teamId) {
    try {
      return new TeamRoute(OrganizationId.from(organizationId), TeamId.from(teamId));
    } catch (IllegalArgumentException failure) {
      throw invalid("route");
    }
  }

  static WorkItemRoute workItemRoute(
      String organizationId,
      String teamId,
      String projectId,
      String workItemId) {
    TeamRoute team = teamRoute(organizationId, teamId);
    try {
      return new WorkItemRoute(
          team.organizationId(),
          team.teamId(),
          WorkProjectId.from(projectId),
          WorkItemId.from(workItemId));
    } catch (IllegalArgumentException failure) {
      throw invalid("route");
    }
  }

  static ActivityFilter teamFilter(
      String workItemId,
      List<String> categories,
      List<String> eventTypes,
      List<String> actorPrincipalIds) {
    try {
      Optional<WorkItemId> item = Optional.ofNullable(workItemId)
          .filter(value -> !value.isBlank())
          .map(WorkItemId::from);
      Set<ActivityCategory> normalizedCategories = values(categories, "categories").stream()
          .map(value -> ActivityCategory.valueOf(value.toUpperCase(Locale.ROOT)))
          .collect(java.util.stream.Collectors.toUnmodifiableSet());
      Set<EventType> normalizedEventTypes = values(eventTypes, "eventTypes").stream()
          .map(EventType::from)
          .collect(java.util.stream.Collectors.toUnmodifiableSet());
      Set<PrincipalId> normalizedActors = values(actorPrincipalIds, "actorPrincipalIds").stream()
          .map(PrincipalId::from)
          .collect(java.util.stream.Collectors.toUnmodifiableSet());
      return new ActivityFilter(item, normalizedCategories, normalizedEventTypes, normalizedActors);
    } catch (ApiRequestException failure) {
      throw failure;
    } catch (IllegalArgumentException failure) {
      throw invalid("filters");
    }
  }

  static ActivityFilter workItemFilter(
      WorkItemId workItemId,
      List<String> categories,
      List<String> eventTypes,
      List<String> actorPrincipalIds) {
    ActivityFilter selected = teamFilter(null, categories, eventTypes, actorPrincipalIds);
    return new ActivityFilter(
        Optional.of(workItemId),
        selected.categories(),
        selected.eventTypes(),
        selected.actorPrincipalIds());
  }

  static String resumeToken(String lastEventId, String after) {
    boolean hasHeader = lastEventId != null && !lastEventId.isBlank();
    boolean hasParameter = after != null && !after.isBlank();
    if (hasHeader && hasParameter && !lastEventId.equals(after)) {
      throw new ApiRequestException(
          HttpStatus.BAD_REQUEST,
          "invalid_cursor",
          "Last-Event-ID and after must identify the same position",
          Map.of("header", ApiHeaders.LAST_EVENT_ID, "parameter", "after"));
    }
    return hasHeader ? lastEventId : after;
  }

  static ApiRequestException unavailable() {
    return new ApiRequestException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "activity_unavailable",
        "Team Activity API is unavailable on this server",
        Map.of());
  }

  private static Set<String> values(List<String> raw, String parameter) {
    LinkedHashSet<String> values = new LinkedHashSet<>();
    Optional.ofNullable(raw).orElseGet(List::of).stream()
        .filter(value -> value != null && !value.isBlank())
        .flatMap(value -> Arrays.stream(value.split(",")))
        .map(String::strip)
        .filter(value -> !value.isBlank())
        .forEach(values::add);
    if (values.size() > MAX_FILTER_VALUES) {
      throw new ApiRequestException(
          HttpStatus.BAD_REQUEST,
          "invalid_request",
          parameter + " accepts at most " + MAX_FILTER_VALUES + " values",
          Map.of("parameter", parameter));
    }
    return Set.copyOf(values);
  }

  private static ApiRequestException invalid(String field) {
    return new ApiRequestException(
        HttpStatus.BAD_REQUEST,
        "invalid_request",
        "Request contains invalid Activity parameters",
        Map.of("field", field));
  }

  record TeamRoute(OrganizationId organizationId, TeamId teamId) {}

  record WorkItemRoute(
      OrganizationId organizationId,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemId workItemId) {}
}
