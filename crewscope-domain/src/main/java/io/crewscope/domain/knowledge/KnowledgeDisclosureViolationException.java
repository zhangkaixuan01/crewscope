package io.crewscope.domain.knowledge;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import java.util.Map;

/**
 * Reports a blocked knowledge publication whose content would disclose secrets to a wider
 * audience (S01 §3.5 disclosure check). The payload carries only the matched high-confidence
 * pattern family name — never the matched text, which is itself the secret being protected.
 */
public final class KnowledgeDisclosureViolationException extends DomainException {

  public KnowledgeDisclosureViolationException(String patternFamily) {
    super(
        new DomainError(
            DomainErrorCode.KNOWLEDGE_DISCLOSURE_DENIED,
            "Knowledge content matches a protected disclosure pattern family ("
                + requireFamily(patternFamily)
                + ") and cannot be published to a wider audience",
            Map.of("patternFamily", requireFamily(patternFamily))));
  }

  private static String requireFamily(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("patternFamily must not be blank");
    }
    return value.strip();
  }
}
