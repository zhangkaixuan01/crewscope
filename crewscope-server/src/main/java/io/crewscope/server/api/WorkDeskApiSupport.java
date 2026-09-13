package io.crewscope.server.api;

import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;

/** Strict parsing for the personal WorkDesk scope and closed responsibility-role filter. */
final class WorkDeskApiSupport {
  private WorkDeskApiSupport() {}

  static Route route(String organizationId, String teamId) {
    try {
      return new Route(OrganizationId.from(organizationId), TeamId.from(teamId));
    } catch (IllegalArgumentException failure) {
      throw invalid("scope");
    }
  }

  static Optional<WorkProjectId> project(String value) {
    if (value == null || value.isBlank()) return Optional.empty();
    try {
      return Optional.of(WorkProjectId.from(value));
    } catch (IllegalArgumentException failure) {
      throw invalid("projectId");
    }
  }

  static Optional<ResponsibilityRole> role(String value) {
    if (value == null || value.isBlank()) return Optional.empty();
    try {
      return Optional.of(ResponsibilityRole.valueOf(value.strip().toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException failure) {
      throw invalid("responsibilityRole");
    }
  }

  private static ApiRequestException invalid(String field) {
    return new ApiRequestException(
        HttpStatus.BAD_REQUEST, "invalid_request", "Request contains invalid WorkDesk parameters", Map.of("field", field));
  }

  record Route(OrganizationId organizationId, TeamId teamId) {}
}
