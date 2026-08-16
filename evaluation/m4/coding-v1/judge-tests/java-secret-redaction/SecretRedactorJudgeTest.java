package io.crewscope.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class SecretRedactorJudgeTest {

  @Test
  void redactsSupportedCredentialFormsCaseInsensitively() {
    String input = "TOKEN=abc Authorization: Bearer xyz api_key='secret value' password=hunter2";
    String redacted = new SecretRedactor().redact(input);
    assertFalse(redacted.contains("abc"));
    assertFalse(redacted.contains("xyz"));
    assertFalse(redacted.contains("secret value"));
    assertFalse(redacted.contains("hunter2"));
    assertEquals(4, redacted.split("\\[REDACTED]", -1).length - 1);
  }

  @Test
  void preservesOrdinaryOutput() {
    assertEquals("tests: 12 passed", new SecretRedactor().redact("tests: 12 passed"));
  }
}
