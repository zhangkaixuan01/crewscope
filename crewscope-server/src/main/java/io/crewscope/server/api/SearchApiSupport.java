package io.crewscope.server.api;

import io.crewscope.domain.search.SearchableObjectType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** Strict parsing and input bounds for unified search. */
final class SearchApiSupport {
  private SearchApiSupport() {}
  static OrganizationId organization(String value) { try { return OrganizationId.from(value); } catch (RuntimeException e) { throw invalid("organizationId"); } }
  static TeamId team(String value) { try { return TeamId.from(value); } catch (RuntimeException e) { throw invalid("teamId"); } }
  static Optional<WorkProjectId> project(String value) { if (value == null || value.isBlank()) return Optional.empty(); try { return Optional.of(WorkProjectId.from(value)); } catch (RuntimeException e) { throw invalid("projectId"); } }
  static String text(String value) { if (value == null || value.strip().isBlank() || value.strip().length() > 100) throw invalid("q"); return value.strip(); }
  static Set<SearchableObjectType> types(List<String> values) {
    if (values == null || values.isEmpty()) return EnumSet.allOf(SearchableObjectType.class);
    EnumSet<SearchableObjectType> result = EnumSet.noneOf(SearchableObjectType.class);
    values.forEach(raw -> { try { result.add(SearchableObjectType.valueOf(raw.strip().toUpperCase(Locale.ROOT))); } catch (RuntimeException e) { throw invalid("types"); } });
    return result;
  }
  private static ApiRequestException invalid(String field) { return new ApiRequestException(HttpStatus.BAD_REQUEST, "invalid_request", "Request contains invalid search parameters", java.util.Map.of("field", field)); }
}
