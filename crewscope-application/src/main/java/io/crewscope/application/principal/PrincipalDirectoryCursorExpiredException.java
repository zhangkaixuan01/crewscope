package io.crewscope.application.principal;

/** Signals that a directory cursor is older than the configured cursor maximum age. */
public final class PrincipalDirectoryCursorExpiredException extends RuntimeException {

    public PrincipalDirectoryCursorExpiredException() {
        super("The principal directory cursor has expired");
    }
}
