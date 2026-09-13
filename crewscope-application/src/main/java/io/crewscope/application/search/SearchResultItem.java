package io.crewscope.application.search;

import io.crewscope.domain.search.SearchableObjectType;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Safe, route-bearing search result. No credential, endpoint or host path is exposed. */
public record SearchResultItem(
    SearchableObjectType objectType, UUID objectId, Optional<UUID> projectId,
    String title, Optional<String> subtitle, String status, Instant updatedAt,
    String route, Optional<String> snippet) {
  public SearchResultItem {
    objectType = Objects.requireNonNull(objectType, "objectType"); objectId = Objects.requireNonNull(objectId, "objectId");
    projectId = Objects.requireNonNull(projectId, "projectId"); title = require(title, "title"); subtitle = Objects.requireNonNull(subtitle, "subtitle");
    status = require(status, "status"); updatedAt = Objects.requireNonNull(updatedAt, "updatedAt"); route = require(route, "route"); snippet = Objects.requireNonNull(snippet, "snippet");
  }
  private static String require(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank"); return value.strip(); }
}
