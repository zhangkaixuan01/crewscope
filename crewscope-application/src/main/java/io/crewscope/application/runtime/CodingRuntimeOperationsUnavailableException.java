package io.crewscope.application.runtime;

/** Raised when the API process does not own the requested Worker environment. */
public final class CodingRuntimeOperationsUnavailableException extends RuntimeException {

    public CodingRuntimeOperationsUnavailableException() {
        super("Coding Runtime operations are unavailable for the requested environment");
    }
}
