package io.crewscope.evaluation;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Deduplicates one logical command by its idempotency key. */
public final class IdempotentWorkService {

  private final Map<String, String> results = new HashMap<>();

  public String execute(String idempotencyKey, Supplier<String> action) {
    if (results.containsKey(idempotencyKey)) {
      return results.get(idempotencyKey);
    }
    String result = action.get();
    results.put(idempotencyKey, result);
    return result;
  }
}
