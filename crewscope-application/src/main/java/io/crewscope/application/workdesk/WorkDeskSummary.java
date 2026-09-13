package io.crewscope.application.workdesk;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable projection returned by the personal WorkDesk query. */
public record WorkDeskSummary(
    String organizationId,
    String teamId,
    Optional<String> projectId,
    Instant generatedAt,
    List<WorkDeskSection> sections) {

  public WorkDeskSummary {
    if (organizationId == null || organizationId.isBlank() || teamId == null || teamId.isBlank()) {
      throw new IllegalArgumentException("WorkDesk scope must not be blank");
    }
    projectId = Objects.requireNonNull(projectId, "projectId");
    generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
    sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
  }
}
