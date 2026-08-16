package io.crewscope.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IdempotentWorkServiceJudgeTest {

  @Test
  void executesOneActionForConcurrentDuplicateKeys() throws Exception {
    IdempotentWorkService service = new IdempotentWorkService();
    AtomicInteger executions = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    List<String> results = java.util.Collections.synchronizedList(new ArrayList<>());
    var pool = Executors.newFixedThreadPool(8);
    try {
      for (int index = 0; index < 8; index++) {
        pool.submit(
            () -> {
              start.await();
              results.add(service.execute("same-key", () -> "result-" + executions.incrementAndGet()));
              return null;
            });
      }
      start.countDown();
      pool.shutdown();
      org.junit.jupiter.api.Assertions.assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, executions.get());
    assertEquals(1, results.stream().distinct().count());
  }

  @Test
  void rejectsBlankKeysAndDoesNotCacheFailures() {
    IdempotentWorkService service = new IdempotentWorkService();
    assertThrows(IllegalArgumentException.class, () -> service.execute(" ", () -> "ignored"));
    assertThrows(IllegalStateException.class, () -> service.execute("key", () -> { throw new IllegalStateException(); }));
    assertEquals("recovered", service.execute("key", () -> "recovered"));
  }
}
