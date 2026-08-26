package io.crewscope.application.activity;

/** Signals that a Team cursor no longer identifies a readable projection position. */
public final class TeamActivityCursorExpiredException extends RuntimeException {

    public TeamActivityCursorExpiredException() {
        super("Team Activity cursor is no longer retained");
    }
}
