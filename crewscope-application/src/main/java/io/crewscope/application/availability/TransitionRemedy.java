package io.crewscope.application.availability;

/**
 * A safe, internal destination that can help resolve a disabled action.
 *
 * <p>Only ever an in-product coordinate: the server never puts an external URL, a Provider endpoint or
 * a filesystem path in a remedy, and a reason with no real next step carries no remedy at all rather
 * than a link to a page that cannot change the outcome.
 */
public record TransitionRemedy(String label, String route) {
  public TransitionRemedy {
    if (label == null || label.isBlank() || route == null || route.isBlank()) {
      throw new IllegalArgumentException("transition remedy label and route must not be blank");
    }
    label = label.strip();
    route = route.strip();
  }
}
