package io.crewscope.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class UserNameNormalizerJudgeTest {

  private final UserNameNormalizer normalizer = new UserNameNormalizer();

  @Test
  void normalizesUnicodeWhitespaceAndLocaleIndependently() {
    Locale original = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      assertEquals("ålice smith", normalizer.normalize("  A\u030Alice\t  SMITH  "));
    } finally {
      Locale.setDefault(original);
    }
  }

  @Test
  void rejectsMissingOrBlankNames() {
    assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(null));
    assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(" \n\t "));
  }
}
