package io.crewscope.server.config.application;

import io.crewscope.domain.retrieval.chunking.ChunkingPolicy;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Durable knowledge-index switches (M10-I01b). {@code enabled} gates refresh-class
 * enqueues only — cleanup-class enqueues keep draining vectors regardless (§10.7). The
 * worker sub-group additionally gates leased execution; both default off, and enabling
 * {@code enabled} without {@code crewscope.knowledge.vector.enabled} is the one illegal
 * combination (health {@code DOWN}, refresh enqueues rejected). {@code max-file-bytes}
 * feeds {@link ChunkingPolicy}, so changing it changes the policy hash and therefore
 * requires a new generation — that is deliberate, not a side effect.
 */
@ConfigurationProperties(prefix = "crewscope.knowledge.index")
public class KnowledgeIndexProperties {

    private boolean enabled = false;
    private final Worker worker = new Worker();
    private int maxChunksPerGeneration = 20000;
    private long maxFileBytes = ChunkingPolicy.DEFAULT_MAX_FILE_BYTES;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Worker getWorker() {
        return worker;
    }

    public int getMaxChunksPerGeneration() {
        return maxChunksPerGeneration;
    }

    public void setMaxChunksPerGeneration(int maxChunksPerGeneration) {
        this.maxChunksPerGeneration = maxChunksPerGeneration;
    }

    public long getMaxFileBytes() {
        return maxFileBytes;
    }

    public void setMaxFileBytes(long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    public int validatedMaxChunksPerGeneration() {
        if (maxChunksPerGeneration < 1) {
            throw new IllegalStateException(
                    "crewscope.knowledge.index.max-chunks-per-generation must be positive");
        }
        return maxChunksPerGeneration;
    }

    /** S01 §3.3 policy with the operator-tunable file ceiling; the hash follows the value. */
    public ChunkingPolicy chunkingPolicy() {
        ChunkingPolicy defaults = ChunkingPolicy.defaults();
        return new ChunkingPolicy(
                defaults.windowLines(),
                defaults.stepLines(),
                defaults.snapSearchLines(),
                defaults.snapFloorLines(),
                maxFileBytes,
                defaults.excludes());
    }

    /** Bounded scheduling and lease settings for the index worker. */
    public static class Worker {

        private boolean enabled = false;
        private String workerId = "knowledge-index-worker-local";
        private Duration pollInterval = Duration.ofSeconds(1);
        private Duration leaseDuration = Duration.ofMinutes(30);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getWorkerId() {
            return workerId;
        }

        public void setWorkerId(String workerId) {
            this.workerId = workerId;
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public Duration getLeaseDuration() {
            return leaseDuration;
        }

        public void setLeaseDuration(Duration leaseDuration) {
            this.leaseDuration = leaseDuration;
        }

        public String validatedWorkerId() {
            String value = Objects.requireNonNull(
                    workerId, "crewscope.knowledge.index.worker.worker-id").strip();
            if (value.isEmpty() || value.length() > 160) {
                throw new IllegalStateException(
                        "crewscope.knowledge.index.worker.worker-id must contain"
                                + " 1 to 160 characters");
            }
            return value;
        }

        public Duration validatedPollInterval() {
            return duration(pollInterval, Duration.ofMillis(100), Duration.ofMinutes(1),
                    "poll-interval");
        }

        public Duration validatedLeaseDuration() {
            return duration(leaseDuration, Duration.ofSeconds(5), Duration.ofHours(1),
                    "lease-duration");
        }

        private static Duration duration(
                Duration value, Duration minimum, Duration maximum, String property) {
            Duration required = Objects.requireNonNull(
                    value, "crewscope.knowledge.index.worker." + property);
            if (required.compareTo(minimum) < 0 || required.compareTo(maximum) > 0) {
                throw new IllegalStateException(
                        "crewscope.knowledge.index.worker." + property
                                + " is outside its supported range");
            }
            return required;
        }
    }
}
