package io.crewscope.application.task;

import io.crewscope.domain.task.TaskTokenJti;

/** Generates a fresh high-entropy identifier for each Task Token issuance. */
@FunctionalInterface
public interface TaskTokenJtiGenerator {
    TaskTokenJti generate();
}
