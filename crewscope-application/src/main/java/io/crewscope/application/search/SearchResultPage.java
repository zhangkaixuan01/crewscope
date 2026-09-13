package io.crewscope.application.search;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record SearchResultPage(List<SearchResultItem> items, Optional<SearchCursor> nextCursor) {
  public SearchResultPage { items = List.copyOf(Objects.requireNonNull(items, "items")); nextCursor = Objects.requireNonNull(nextCursor, "nextCursor"); }
}
