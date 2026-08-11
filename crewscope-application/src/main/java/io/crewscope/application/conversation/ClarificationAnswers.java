package io.crewscope.application.conversation;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Bounded user answers keyed only by fields declared in a pending clarification request. */
public record ClarificationAnswers(Map<String, String> values) {

  public static final int MAX_ANSWERS = 10;
  public static final int MAX_ANSWER_LENGTH = 1_000;
  public static final String FIELD_KEY_PATTERN = StructuredOutputPatterns.FIELD_KEY;

  private static final Pattern FIELD_KEY = Pattern.compile(FIELD_KEY_PATTERN);

  public ClarificationAnswers {
    Map<String, String> source = Objects.requireNonNull(values, "values");
    if (source.isEmpty() || source.size() > MAX_ANSWERS) {
      throw new DomainValidationException(
          "clarification.answers", "must contain between 1 and " + MAX_ANSWERS + " answers");
    }
    Map<String, String> normalized = new TreeMap<>();
    source.forEach(
        (key, value) -> {
          if (key == null || !FIELD_KEY.matcher(key).matches()) {
            throw new DomainValidationException(
                "clarification.answers.fieldKey", "has an invalid format");
          }
          if (value == null || value.isBlank()) {
            throw new DomainValidationException(
                "clarification.answers." + key, "must not be blank");
          }
          String answer = value.strip();
          if (answer.length() > MAX_ANSWER_LENGTH) {
            throw new DomainValidationException(
                "clarification.answers." + key,
                "must contain at most " + MAX_ANSWER_LENGTH + " characters");
          }
          normalized.put(key, answer);
        });
    values = Collections.unmodifiableMap(new LinkedHashMap<>(normalized));
  }

  /** Produces a deterministic user-visible Conversation message without exposing Tool metadata. */
  public String toMarkdown() {
    return values.entrySet().stream()
        .map(entry -> "- **" + entry.getKey() + "**: " + entry.getValue())
        .reduce("澄清回答：", (left, right) -> left + "\n" + right);
  }

  /** Produces stable request-hash input independent of JSON object insertion order. */
  public String canonicalValue() {
    StringBuilder canonical = new StringBuilder();
    values.forEach(
        (key, value) -> {
          appendLengthPrefixed(canonical, key);
          appendLengthPrefixed(canonical, value);
        });
    return canonical.toString();
  }

  private static void appendLengthPrefixed(StringBuilder target, String value) {
    target.append(value.length()).append(':').append(value);
  }
}
