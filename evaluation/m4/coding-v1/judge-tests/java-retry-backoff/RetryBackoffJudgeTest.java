package io.crewscope.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RetryBackoffJudgeTest {

  @Test
  void capsLargeAttemptsWithoutOverflow() {
    RetryBackoff backoff = new RetryBackoff(250, 30_000);
    assertEquals(250, backoff.delayMillis(0));
    assertEquals(30_000, backoff.delayMillis(62));
  }

  @Test
  void validatesConstructorAndAttempt() {
    assertThrows(IllegalArgumentException.class, () -> new RetryBackoff(0, 100));
    assertThrows(IllegalArgumentException.class, () -> new RetryBackoff(200, 100));
    assertThrows(
        IllegalArgumentException.class, () -> new RetryBackoff(100, 200).delayMillis(-1));
  }
}
