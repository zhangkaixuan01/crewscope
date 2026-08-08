package io.crewscope.application.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ConversationStructuredOutputTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsCompleteTaskIntentV1AndKeepsItsCollectionsImmutable() {
        TaskIntentV1 output = validTaskIntent();

        assertTrue(VALIDATOR.validate(output).isEmpty());
        assertEquals("1", output.schemaVersion());
        assertThrows(
                UnsupportedOperationException.class,
                () -> output.acceptanceCriteria().add("Mutation"));
    }

    @Test
    void taskIntentSchemaRequiresObjectiveCriteriaProjectAndOwner() {
        TaskIntentV1 output = new TaskIntentV1(
                "2", " ", List.of(), "not-a-uuid", "", null, null);

        Set<String> paths = paths(VALIDATOR.validate(output));

        assertTrue(paths.contains("schemaVersion"));
        assertTrue(paths.contains("objective"));
        assertTrue(paths.contains("acceptanceCriteria"));
        assertTrue(paths.contains("workProjectId"));
        assertTrue(paths.contains("ownerMemberId"));
    }

    @Test
    void optionalResponsibilityIdentifiersMustStillBeCanonical() {
        TaskIntentV1 output = new TaskIntentV1(
                "1",
                "Build conversations",
                List.of("History works"),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "agent-one",
                "reviewer-one");

        Set<String> paths = paths(VALIDATOR.validate(output));

        assertEquals(Set.of("executorPrincipalId", "gateReviewerMemberId"), paths);
    }

    @Test
    void acceptsBoundedClarificationQuestionsAndKeepsThemImmutable() {
        ClarificationRequestV1 output = new ClarificationRequestV1(
                "1",
                "The target project is ambiguous",
                List.of(new ClarificationQuestionV1(
                        "work_project",
                        "Which WorkProject should receive the work?",
                        "Two active projects match the discussion.",
                        true,
                        List.of("CrewScope", "Platform"))));

        assertTrue(VALIDATOR.validate(output).isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> output.questions().add(output.questions().get(0)));
        assertThrows(
                UnsupportedOperationException.class,
                () -> output.questions().get(0).choices().add("Another"));
    }

    @Test
    void clarificationSchemaValidatesNestedQuestionShape() {
        ClarificationRequestV1 output = new ClarificationRequestV1(
                "invalid",
                " ",
                List.of(new ClarificationQuestionV1(
                        "Work Project",
                        " ",
                        "x".repeat(1_001),
                        true,
                        List.of(" "))));

        Set<String> paths = paths(VALIDATOR.validate(output));

        assertTrue(paths.contains("schemaVersion"));
        assertTrue(paths.contains("summary"));
        assertTrue(paths.contains("questions[0].fieldKey"));
        assertTrue(paths.contains("questions[0].question"));
        assertTrue(paths.contains("questions[0].context"));
        assertTrue(paths.contains("questions[0].choices[0].<list element>"));
    }

    @Test
    void versionedOutputsRoundTripThroughTheConfiguredJacksonGeneration() {
        ObjectMapper mapper = new ObjectMapper();
        TaskIntentV1 taskIntent = validTaskIntent();
        ClarificationRequestV1 clarification = new ClarificationRequestV1(
                "1",
                "Need one answer",
                List.of(new ClarificationQuestionV1(
                        "repository",
                        "Which repository is in scope?",
                        null,
                        true,
                        List.of())));

        assertEquals(
                taskIntent,
                mapper.readValue(mapper.writeValueAsString(taskIntent), TaskIntentV1.class));
        assertEquals(
                clarification,
                mapper.readValue(
                        mapper.writeValueAsString(clarification),
                        ClarificationRequestV1.class));
    }

    private static TaskIntentV1 validTaskIntent() {
        return new TaskIntentV1(
                "1",
                "Build conversation history",
                List.of("Messages retain stable order", "Access is auditable"),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString());
    }

    private static Set<String> paths(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream()
                .map(value -> value.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }
}
