package io.crewscope.infrastructure.agentscope;

import io.crewscope.application.execution.AgentStateUnavailableException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.SetParams;

/** Redis lease that enforces the single active Agent-execution instance required by M2. */
public final class RedisSingleActiveExecutionGuard implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisSingleActiveExecutionGuard.class);
    private static final String RENEW_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end";
    private static final String RELEASE_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end";

    private final UnifiedJedis jedis;
    private final String ownerKey;
    private final String ownerToken;
    private final long leaseMillis;
    private final long renewalMillis;
    private final ScheduledExecutorService renewer;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public RedisSingleActiveExecutionGuard(
            UnifiedJedis jedis,
            CrewScopeRedisKeyspace keyspace,
            String instanceId,
            Duration leaseDuration,
            Duration renewalInterval) {
        this(jedis, keyspace, "default", instanceId, leaseDuration, renewalInterval);
    }

    public RedisSingleActiveExecutionGuard(
            UnifiedJedis jedis,
            CrewScopeRedisKeyspace keyspace,
            String ownershipScope,
            String instanceId,
            Duration leaseDuration,
            Duration renewalInterval) {
        this.jedis = Objects.requireNonNull(jedis, "jedis");
        this.ownerKey = Objects.requireNonNull(keyspace, "keyspace")
                .activeExecutionOwnerKey(ownershipScope);
        String requiredInstanceId = requireInstanceId(instanceId);
        this.ownerToken = requiredInstanceId + ":" + UUID.randomUUID();
        this.leaseMillis = requireMillis(leaseDuration, "leaseDuration");
        this.renewalMillis = requireMillis(renewalInterval, "renewalInterval");
        if (renewalMillis * 3 >= leaseMillis) {
            throw new IllegalArgumentException(
                    "renewalInterval must be less than one third of leaseDuration");
        }
        this.renewer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "crewscope-agent-execution-owner-renewer");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Acquires ownership once and rejects startup while another live owner exists. */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        if (closed.get()) {
            throw new SingleActiveAgentExecutionException(
                    "Agent execution ownership guard is already closed");
        }
        try {
            String result = jedis.set(
                    ownerKey,
                    ownerToken,
                    SetParams.setParams().nx().px(leaseMillis));
            if (!"OK".equals(result)) {
                throw new SingleActiveAgentExecutionException(
                        "Another CrewScope instance already owns Agent execution");
            }
            active.set(true);
            renewer.scheduleWithFixedDelay(
                    this::renewSafely, renewalMillis, renewalMillis, TimeUnit.MILLISECONDS);
        } catch (SingleActiveAgentExecutionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SingleActiveAgentExecutionException(
                    "Unable to acquire CrewScope Agent execution ownership");
        }
    }

    /** Fails closed after ownership loss or when Redis cannot confirm the current token. */
    public void requireOwnership() {
        if (!active.get() || closed.get()) {
            throw new AgentStateUnavailableException();
        }
        try {
            if (!ownerToken.equals(jedis.get(ownerKey))) {
                active.set(false);
                throw new AgentStateUnavailableException();
            }
        } catch (AgentStateUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            active.set(false);
            throw new AgentStateUnavailableException(exception);
        }
    }

    public boolean isActive() {
        return active.get() && !closed.get();
    }

    private void renewSafely() {
        if (!active.get() || closed.get()) {
            return;
        }
        try {
            Object renewed = jedis.eval(
                    RENEW_SCRIPT,
                    List.of(ownerKey),
                    List.of(ownerToken, Long.toString(leaseMillis)));
            if (!Long.valueOf(1L).equals(renewed)) {
                active.set(false);
                log.error("CrewScope Agent execution ownership was lost; new calls will fail closed");
            }
        } catch (RuntimeException exception) {
            active.set(false);
            log.error(
                    "CrewScope Agent execution ownership renewal failed; "
                            + "new calls will fail closed; failureCode=REDIS_OWNERSHIP_RENEWAL_FAILED");
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        boolean wasActive = active.getAndSet(false);
        renewer.shutdownNow();
        if (!wasActive) {
            return;
        }
        try {
            jedis.eval(RELEASE_SCRIPT, List.of(ownerKey), List.of(ownerToken));
        } catch (RuntimeException exception) {
            // The finite lease is the crash-safe cleanup path when Redis cannot accept release.
            log.warn(
                    "Unable to release CrewScope Agent execution ownership cleanly; "
                            + "failureCode=REDIS_OWNERSHIP_RELEASE_FAILED");
        }
    }

    private static String requireInstanceId(String value) {
        String required = Objects.requireNonNull(value, "instanceId").strip();
        if (required.isEmpty() || required.length() > 128 || containsControl(required)) {
            throw new IllegalArgumentException(
                    "instanceId must contain 1 to 128 printable characters");
        }
        return required;
    }

    private static long requireMillis(Duration value, String field) {
        Duration required = Objects.requireNonNull(value, field);
        if (required.isZero() || required.isNegative() || required.toMillis() < 1) {
            throw new IllegalArgumentException(field + " must be at least one millisecond");
        }
        return required.toMillis();
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }
}
