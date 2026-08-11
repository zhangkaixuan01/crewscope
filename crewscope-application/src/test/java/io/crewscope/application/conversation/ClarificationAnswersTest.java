package io.crewscope.application.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies deterministic and unambiguous clarification-answer normalization. */
class ClarificationAnswersTest {

  @Test
  void canonicalValueIsIndependentOfInputOrder() {
    Map<String, String> reversed = new LinkedHashMap<>();
    reversed.put("repository", "crewscope-java");
    reversed.put("branch", "main");

    ClarificationAnswers first = new ClarificationAnswers(reversed);
    ClarificationAnswers second =
        new ClarificationAnswers(Map.of("branch", "main", "repository", "crewscope-java"));

    assertEquals(first.values(), second.values());
    assertEquals(first.canonicalValue(), second.canonicalValue());
  }

  @Test
  void canonicalValuePreservesFieldBoundariesEvenWhenAnswersContainSeparators() {
    ClarificationAnswers oneField =
        new ClarificationAnswers(Map.of("a", "x\u0001b\u0000y"));
    ClarificationAnswers twoFields =
        new ClarificationAnswers(Map.of("a", "x", "b", "y"));

    assertNotEquals(oneField.canonicalValue(), twoFields.canonicalValue());
  }
}
