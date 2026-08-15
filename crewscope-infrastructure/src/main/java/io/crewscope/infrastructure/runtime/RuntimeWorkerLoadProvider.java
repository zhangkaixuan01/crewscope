package io.crewscope.infrastructure.runtime;

/** Supplies the authoritative number of TaskExecutions currently owned by this JVM Worker. */
@FunctionalInterface
public interface RuntimeWorkerLoadProvider {

    int activeExecutions();
}
