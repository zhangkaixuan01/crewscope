package io.crewscope.application.task;

/** Read-only verifier used by server request middleware in every deployment profile. */
@FunctionalInterface
public interface TaskTokenAuthenticator {
    TaskTokenExecutionContext authenticate(String token);
}
