package io.crewscope.application.availability;

/**
 * How prominently a surface should offer one executable action.
 *
 * <p>Presentation metadata only: it never decides whether an action is available, and the command
 * side never reads it. Shared by every object type that offers actions so a menu does not have to
 * guess a button variant from an action identifier.
 */
public enum ActionStrength {
  /** The expected next step; a surface may render it as the primary button. */
  PRIMARY,
  /** A legitimate but non-default step. */
  SECONDARY,
  /** Destructive or hard to reverse; a surface must confirm before executing. */
  DANGER
}
