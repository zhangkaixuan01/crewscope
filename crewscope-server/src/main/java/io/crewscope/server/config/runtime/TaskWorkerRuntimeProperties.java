package io.crewscope.server.config.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Type-safe AgentScope Task Worker execution and process-loop configuration. */
@ConfigurationProperties(prefix = "crewscope.runtime.task-worker")
public class TaskWorkerRuntimeProperties {

    private String modelId = "crewscope-primary";
    private String fallbackModelId = "";
    private String systemPrompt = "You are CrewScope's controlled Task Orchestrator. Produce a valid controlled plan and execute only authorized fixture tools.";
    private int maxIterations = 20;
    private int maxRetries = 3;
    private int maxStepRunAttempts = 3;
    private Path runtimeRoot = Path.of("./var/crewscope/task-agent-runtime");
    private Duration pollInterval = Duration.ofMillis(250);
    private Duration gracefulShutdownTimeout = Duration.ofSeconds(30);
    private Duration taskTokenLifetime = Duration.ofMinutes(5);
    private int recoveryCandidateLimit = 16;
    private int maximumReconcileSize = 100;

    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getFallbackModelId() { return fallbackModelId; }
    public void setFallbackModelId(String fallbackModelId) {
        this.fallbackModelId = fallbackModelId;
    }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public int getMaxStepRunAttempts() { return maxStepRunAttempts; }
    public void setMaxStepRunAttempts(int maxStepRunAttempts) {
        this.maxStepRunAttempts = maxStepRunAttempts;
    }
    public Path getRuntimeRoot() { return runtimeRoot; }
    public void setRuntimeRoot(Path runtimeRoot) { this.runtimeRoot = runtimeRoot; }
    public Duration getPollInterval() { return pollInterval; }
    public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
    public Duration getGracefulShutdownTimeout() { return gracefulShutdownTimeout; }
    public void setGracefulShutdownTimeout(Duration gracefulShutdownTimeout) {
        this.gracefulShutdownTimeout = gracefulShutdownTimeout;
    }
    public Duration getTaskTokenLifetime() { return taskTokenLifetime; }
    public void setTaskTokenLifetime(Duration taskTokenLifetime) {
        this.taskTokenLifetime = taskTokenLifetime;
    }
    public int getRecoveryCandidateLimit() { return recoveryCandidateLimit; }
    public void setRecoveryCandidateLimit(int recoveryCandidateLimit) {
        this.recoveryCandidateLimit = recoveryCandidateLimit;
    }
    public int getMaximumReconcileSize() { return maximumReconcileSize; }
    public void setMaximumReconcileSize(int maximumReconcileSize) {
        this.maximumReconcileSize = maximumReconcileSize;
    }

    Optional<String> fallbackModelId() {
        return Optional.ofNullable(fallbackModelId)
                .map(String::strip)
                .filter(value -> !value.isEmpty());
    }
}
