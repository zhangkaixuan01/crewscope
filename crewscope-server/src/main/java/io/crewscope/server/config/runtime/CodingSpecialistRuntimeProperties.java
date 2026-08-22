package io.crewscope.server.config.runtime;

import java.nio.file.Path;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Type-safe AgentScope Coding Specialist configuration owned by Worker deployments. */
@ConfigurationProperties(prefix = "crewscope.runtime.coding-specialist")
public class CodingSpecialistRuntimeProperties {

    private String modelId = "crewscope-primary";
    private String fallbackModelId = "";
    private String compactionModelId = "crewscope-primary";
    private String systemPrompt = "You are CrewScope's controlled Coding Specialist. Load and follow the fixed coding skill. Maintain exactly two Todos named Implement requested change and Verify and deliver. Call todo_write only once to initialize both and once to complete both; never create or update intermediate Todos. Use only registered tools, run only required platform acceptance, inspect the final diff once and return the structured output immediately.";
    private int maxIterations = 60;
    private int maxRetries = 3;
    private double temperature = 0.0;
    private double topP = 1.0;
    private int maxOutputTokens = 8_192;
    // Compact after a complete inspect/edit exchange. Keeping four recent messages preserves the
    // active tool pair and the decision that led to it while the summary retains durable context.
    private int compactionTriggerMessages = 16;
    private int compactionKeepMessages = 4;
    private int toolResultEvictionChars = 8_192;
    private int toolResultPreviewChars = 1_024;
    private Path runtimeRoot = Path.of("./var/crewscope/coding-agent-runtime");

    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getFallbackModelId() { return fallbackModelId; }
    public void setFallbackModelId(String fallbackModelId) {
        this.fallbackModelId = fallbackModelId;
    }
    public String getCompactionModelId() { return compactionModelId; }
    public void setCompactionModelId(String compactionModelId) {
        this.compactionModelId = compactionModelId;
    }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public double getTopP() { return topP; }
    public void setTopP(double topP) { this.topP = topP; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }
    public int getCompactionTriggerMessages() { return compactionTriggerMessages; }
    public void setCompactionTriggerMessages(int compactionTriggerMessages) {
        this.compactionTriggerMessages = compactionTriggerMessages;
    }
    public int getCompactionKeepMessages() { return compactionKeepMessages; }
    public void setCompactionKeepMessages(int compactionKeepMessages) {
        this.compactionKeepMessages = compactionKeepMessages;
    }
    public int getToolResultEvictionChars() { return toolResultEvictionChars; }
    public void setToolResultEvictionChars(int toolResultEvictionChars) {
        this.toolResultEvictionChars = toolResultEvictionChars;
    }
    public int getToolResultPreviewChars() { return toolResultPreviewChars; }
    public void setToolResultPreviewChars(int toolResultPreviewChars) {
        this.toolResultPreviewChars = toolResultPreviewChars;
    }
    public Path getRuntimeRoot() { return runtimeRoot; }
    public void setRuntimeRoot(Path runtimeRoot) { this.runtimeRoot = runtimeRoot; }

    Optional<String> fallbackModelId() {
        return Optional.ofNullable(fallbackModelId)
                .map(String::strip)
                .filter(value -> !value.isEmpty());
    }
}
