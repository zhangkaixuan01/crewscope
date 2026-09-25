package io.crewscope.server.api;

import io.crewscope.application.workitem.WorkItemFilterFingerprint;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** Strict parsing for the personal WorkDesk scope and closed responsibility-role filter. */
final class WorkDeskApiSupport {
  private static final Set<String> SECTION_KEYS =
      Set.of("HUMAN_GATE", "REVIEW", "BLOCKED", "WORK_ITEM", "TASK_EXECUTION", "INBOX");

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

  /** Only the six known sections continue; an unknown key is a new query, not a 500. */
  static String sectionKey(String value) {
    if (value == null || !SECTION_KEYS.contains(value)) {
      throw invalid("sectionKey");
    }
    return value;
  }

  /**
   * Stable scope fingerprint of the desk filters a cursor was issued under, so replaying a
   * continuation with a changed filter is rejected rather than silently skipping or repeating rows.
   */
  static WorkItemFilterFingerprint fingerprint(
      Optional<WorkProjectId> project, Optional<ResponsibilityRole> role, boolean onlyNeedsAction) {
    String canonical = "work-desk-filter-v1"
        + "\nprojectId=" + project.map(WorkProjectId::toString).orElse("")
        + "\nresponsibilityRole=" + role.map(Enum::name).orElse("")
        + "\nonlyNeedsAction=" + onlyNeedsAction;
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(canonical.getBytes(StandardCharsets.UTF_8));
      return new WorkItemFilterFingerprint(HexFormat.of().formatHex(digest));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", failure);
    }
  }

  record Route(OrganizationId organizationId, TeamId teamId) {}
}
