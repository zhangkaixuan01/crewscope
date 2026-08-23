package io.crewscope.domain.agent;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Regression tests for unambiguous resolved Agent component hashes. */
class ResolvedAgentExecutionConfigurationHashTest {

    @Test
    void promptHashPreservesBaselineAndSupplementalInstructionBoundaries() {
        ResolvedAgentExecutionConfiguration baselineContainsDelimiter =
                ResolvedAgentExecutionTestFixture.createWithPromptParts(
                        "Review source|then tests", Optional.of("Return evidence"));
        ResolvedAgentExecutionConfiguration supplementalContainsDelimiter =
                ResolvedAgentExecutionTestFixture.createWithPromptParts(
                        "Review source", Optional.of("then tests|Return evidence"));

        assertNotEquals(
                baselineContainsDelimiter.promptHash(),
                supplementalContainsDelimiter.promptHash());
    }
}
