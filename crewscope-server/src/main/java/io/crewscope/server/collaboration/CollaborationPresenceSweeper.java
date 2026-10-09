package io.crewscope.server.collaboration;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Periodic presence sweep (M11-I01b acceptance): lazy eviction only fires on scopes someone
 * reads, so expired members would otherwise linger forever in scopes nobody visits. This
 * walks every scope key with SCAN — including the untouched ones — and evicts members whose
 * expiry score has passed. Best-effort by design: a failed round logs and the next round
 * retries, and correctness never depends on it because {@link
 * CollaborationPresenceStore#listPresent} always evicts before reading.
 */
public final class CollaborationPresenceSweeper implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(CollaborationPresenceSweeper.class);
  private static final long SCAN_BATCH = 500;

  private final ReactiveStringRedisTemplate redis;
  private final CollaborationPresenceKeyspace keyspace;
  private final Duration sweepInterval;
  private final Clock clock;
  private volatile Disposable sweep;

  public CollaborationPresenceSweeper(
      ReactiveStringRedisTemplate redis,
      CollaborationPresenceKeyspace keyspace,
      Duration sweepInterval,
      Clock clock) {
    this.redis = redis;
    this.keyspace = keyspace;
    this.sweepInterval = sweepInterval;
    this.clock = clock;
  }

  @Override
  public void start() {
    if (sweep != null) {
      return;
    }
    sweep =
        Flux.interval(sweepInterval)
            .onBackpressureDrop()
            .concatMap(tick -> sweepOnce().onErrorResume(this::logFailedRound))
            .subscribe();
  }

  @Override
  public void stop() {
    Disposable running = sweep;
    sweep = null;
    if (running != null) {
      running.dispose();
    }
  }

  @Override
  public boolean isRunning() {
    return sweep != null && !sweep.isDisposed();
  }

  /** One full round: every scope key, each evicted of expired members. */
  Mono<Void> sweepOnce() {
    double now = clock.millis();
    return redis
        .scan(
            ScanOptions.scanOptions()
                .match(keyspace.scopePattern())
                .count(SCAN_BATCH)
                .build())
        .concatMap(
            key -> redis.opsForZSet().removeRangeByScore(key, Range.closed(0.0, now)))
        .then();
  }

  private Mono<Void> logFailedRound(Throwable failure) {
    log.warn("collaboration presence sweep round failed; next round retries", failure);
    return Mono.empty();
  }
}
