package io.crewscope.evaluation;

/** Calculates an exponential retry delay. */
public final class RetryBackoff {

  private final long baseMillis;
  private final long maximumMillis;

  public RetryBackoff(long baseMillis, long maximumMillis) {
    this.baseMillis = baseMillis;
    this.maximumMillis = maximumMillis;
  }

  public long delayMillis(int attempt) {
    return Math.min(maximumMillis, baseMillis * (1L << attempt));
  }
}
