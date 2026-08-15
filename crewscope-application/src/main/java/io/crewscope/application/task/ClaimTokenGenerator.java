package io.crewscope.application.task;

import io.crewscope.domain.task.ClaimToken;

/** Generates a fresh cryptographic secret for one successful Claim. */
@FunctionalInterface
public interface ClaimTokenGenerator {
    ClaimToken generate();
}
