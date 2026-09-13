package io.crewscope.application.workitem;

/** A safe, internal destination that can help resolve a transition block. */
public record WorkItemTransitionRemedy(String label, String route) {
  public WorkItemTransitionRemedy {
    if (label == null || label.isBlank() || route == null || route.isBlank()) {
      throw new IllegalArgumentException("transition remedy label and route must not be blank");
    }
    label = label.strip();
    route = route.strip();
  }
}
