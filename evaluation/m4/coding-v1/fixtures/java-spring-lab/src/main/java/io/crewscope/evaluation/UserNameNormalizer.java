package io.crewscope.evaluation;

/** Normalizes a user-supplied display name before identity matching. */
public final class UserNameNormalizer {

  public String normalize(String value) {
    return value.trim().toLowerCase();
  }
}
