package io.crewscope.domain.review;

/** Whether a line comment can still be mapped to the current Diff snapshot. */
public enum ReviewCommentAnchorState {
    ACTIVE,
    OUTDATED
}
