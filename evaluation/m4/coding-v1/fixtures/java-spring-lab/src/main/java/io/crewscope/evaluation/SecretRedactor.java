package io.crewscope.evaluation;

/** Removes credential-like values from public command output. */
public final class SecretRedactor {

  public String redact(String text) {
    return text.replaceAll("token=[^\\s]+", "token=[REDACTED]");
  }
}
