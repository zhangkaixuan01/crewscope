package io.crewscope.application.workdesk;

/** Read port for the bounded, derived WorkDesk projection. */
public interface WorkDeskRepository {

  /** The six-section first screen: every section truncated to the query's section page size. */
  WorkDeskSummary summarize(WorkDeskQuery query);

  /**
   * One section continued from a signed keyset position (M9b-A06).
   *
   * <p>The position's {@code sectionKey} selects the section; its remaining components are the
   * section's own ordering keys, which the implementation turns back into the keyset predicate. The
   * answer carries the section's true full-set {@code total} and the next position exactly when
   * more rows follow the returned page.
   */
  WorkDeskSection summarizeSection(WorkDeskQuery query, WorkDeskSectionPosition position);
}
