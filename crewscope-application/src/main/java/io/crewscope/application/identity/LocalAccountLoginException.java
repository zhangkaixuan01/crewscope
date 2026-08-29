package io.crewscope.application.identity;

/** Fixed local-login failure that does not reveal account lookup, status, lock or password facts. */
public final class LocalAccountLoginException extends RuntimeException {

    public LocalAccountLoginException() {
        super("The submitted credentials could not be authenticated");
    }
}
