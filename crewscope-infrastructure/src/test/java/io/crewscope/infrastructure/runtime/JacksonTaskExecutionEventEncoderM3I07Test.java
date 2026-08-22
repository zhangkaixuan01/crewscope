package io.crewscope.infrastructure.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.execution.ExecutionFailure;
import io.crewscope.application.execution.ExecutionFailureCategory;
import io.crewscope.application.execution.ExecutionInterruptKind;
import io.crewscope.application.execution.ExecutionInterruptToken;
import io.crewscope.application.execution.TaskExecutionEvent;
import io.crewscope.application.execution.TaskExecutionEventEncoding;
import io.crewscope.application.execution.TaskExecutionEventPayload;
import io.crewscope.application.execution.StructuredOutputSpec;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.RuntimeArtifactId;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class JacksonTaskExecutionEventEncoderM3I07Test {

    private static final UtcTimestamp OCCURRED_AT =
            UtcTimestamp.parse("2026-08-15T04:30:00Z");
    private final JacksonTaskExecutionEventEncoder encoder =
            new JacksonTaskExecutionEventEncoder(new ObjectMapper());

    @Test
    void fingerprintsSecretsButPublishesOnlyControlledApprovalFields() {
        TaskExecutionEvent first = event(new TaskExecutionEventPayload.ApprovalRequired(
                new ExecutionInterruptToken("secret-token-one"),
                ExecutionInterruptKind.TOOL_APPROVAL,
                "Approve the validated plan."));
        TaskExecutionEvent second = event(new TaskExecutionEventPayload.ApprovalRequired(
                new ExecutionInterruptToken("secret-token-two"),
                ExecutionInterruptKind.TOOL_APPROVAL,
                "Approve the validated plan."));

        TaskExecutionEventEncoding encoded = encoder.encode(first);

        assertNotEquals(encoded.fingerprint(), encoder.encode(second).fingerprint());
        assertEquals("APPROVAL_REQUIRED", encoded.publicEvent().eventKind());
        assertEquals(Optional.of("Approve the validated plan."),
                encoded.publicEvent().safeText());
        assertEquals(Optional.of("TOOL_APPROVAL"), encoded.publicEvent().status());
        assertFalse(encoded.publicEvent().contentHash().isPresent());
        assertFalse(encoded.publicEvent().referenceId().isPresent());
        assertFalse(encoded.publicEvent().toString().contains("secret-token"));
    }

    @Test
    void removesToolCallIdentityAndPreservesSafeFailureAndArtifactReference() {
        RuntimeArtifactId artifactId = RuntimeArtifactId.generate();
        TaskExecutionEvent event = event(new TaskExecutionEventPayload.ToolResult(
                "private-tool-call-id",
                "fixture_validate",
                false,
                Optional.of(artifactId),
                Optional.of(new ExecutionFailure(
                        ExecutionFailureCategory.TOOL_FAILED,
                        true,
                        "The validation fixture failed safely.",
                        Optional.of("FIXTURE.VALIDATION-FAILED")))));

        TaskExecutionEventEncoding encoded = encoder.encode(event);

        assertEquals(Optional.of("fixture_validate"), encoded.publicEvent().name());
        assertEquals(Optional.of(false), encoded.publicEvent().succeeded());
        assertEquals(Optional.of(artifactId.value()), encoded.publicEvent().referenceId());
        assertEquals(Optional.of("FIXTURE.VALIDATION-FAILED"),
                encoded.publicEvent().failure().orElseThrow().runtimeCode());
        assertFalse(encoded.publicEvent().toString().contains("private-tool-call-id"));
    }

    @Test
    void publishesRetryFallbackAndUsageAsBoundedStableFacts() {
        TaskExecutionEventEncoding retry = encoder.encode(event(
                new TaskExecutionEventPayload.ModelTransition(
                        TaskExecutionEventPayload.ModelTransitionType.RETRYING,
                        TaskExecutionEventPayload.ModelRole.PRIMARY,
                        2,
                        3)));
        TaskExecutionEventEncoding usage = encoder.encode(event(
                new TaskExecutionEventPayload.UsageReported(10, 4, 2, 14)));

        assertEquals(Optional.of("RETRYING"), retry.publicEvent().status());
        assertEquals(Optional.of("PRIMARY"), retry.publicEvent().name());
        assertEquals(Optional.of(2), retry.publicEvent().modelAttempt());
        assertEquals(Optional.of(3), retry.publicEvent().modelMaxAttempts());
        assertTrue(usage.publicEvent().usage().isPresent());
        assertEquals(14, usage.publicEvent().usage().orElseThrow().totalTokens());
    }

    @Test
    void preservesWhitespaceOnlyStreamingDeltas() {
        TaskExecutionEventEncoding encoded = encoder.encode(event(
                new TaskExecutionEventPayload.TextDelta(" \n\t")));

        assertEquals("TEXT_DELTA", encoded.publicEvent().eventKind());
        assertEquals(Optional.of(" \n\t"), encoded.publicEvent().safeText());
    }

    @Test
    void publishesCanonicalStructuredOutputHashWithoutPublishingItsValue() {
        StructuredOutputSpec<String> spec = new StructuredOutputSpec<>(
                "test-result/v1", String.class);
        TaskExecutionEventEncoding encoded = encoder.encode(event(
                new TaskExecutionEventPayload.StructuredOutput<>(spec, "private-result")));

        assertEquals("STRUCTURED_OUTPUT", encoded.publicEvent().eventKind());
        assertEquals(Optional.of("test-result/v1"), encoded.publicEvent().name());
        assertTrue(encoded.publicEvent().contentHash().orElseThrow()
                .matches("[0-9a-f]{64}"));
        assertFalse(encoded.publicEvent().toString().contains("private-result"));
    }

    private static TaskExecutionEvent event(TaskExecutionEventPayload payload) {
        return new TaskExecutionEvent(
                TaskExecutionId.generate(),
                1,
                AgentRunId.generate(),
                1,
                1,
                OCCURRED_AT,
                payload);
    }
}
