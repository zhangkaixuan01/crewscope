package io.crewscope.application.organization;

/** Signals that an organization member directory cursor is older than the configured maximum age. */
public final class OrganizationMemberDirectoryCursorExpiredException extends RuntimeException {

    public OrganizationMemberDirectoryCursorExpiredException() {
        super("The organization member directory cursor has expired");
    }
}
