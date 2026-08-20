package io.crewscope.infrastructure.workspace.repository;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment ceilings used to derive one immutable WorkspacePolicy per Coding attempt. */
@ConfigurationProperties("crewscope.coding.execution")
public class CodingWorkspaceExecutionProperties {

    private Duration retention = Duration.ofDays(7);
    private int cpuCount = 2;
    private int memoryMib = 2048;
    private int pids = 256;
    private int maxCommandDurationSeconds = 900;
    private long maxCommandOutputBytes = 1_048_576;
    private int maxCommandCalls = 32;
    private int maxChangedFiles = 100;
    private long maxSingleFileBytes = 1_048_576;
    private int maxWriteOperations = 32;
    private long maxWrittenBytes = 16_777_216;
    private long maxDiffBytes = 8_388_608;
    private int maxTestRepairRounds = 2;

    public Duration getRetention() { return retention; }
    public void setRetention(Duration retention) { this.retention = retention; }
    public int getCpuCount() { return cpuCount; }
    public void setCpuCount(int cpuCount) { this.cpuCount = cpuCount; }
    public int getMemoryMib() { return memoryMib; }
    public void setMemoryMib(int memoryMib) { this.memoryMib = memoryMib; }
    public int getPids() { return pids; }
    public void setPids(int pids) { this.pids = pids; }
    public int getMaxCommandDurationSeconds() { return maxCommandDurationSeconds; }
    public void setMaxCommandDurationSeconds(int value) { this.maxCommandDurationSeconds = value; }
    public long getMaxCommandOutputBytes() { return maxCommandOutputBytes; }
    public void setMaxCommandOutputBytes(long value) { this.maxCommandOutputBytes = value; }
    public int getMaxCommandCalls() { return maxCommandCalls; }
    public void setMaxCommandCalls(int value) { this.maxCommandCalls = value; }
    public int getMaxChangedFiles() { return maxChangedFiles; }
    public void setMaxChangedFiles(int value) { this.maxChangedFiles = value; }
    public long getMaxSingleFileBytes() { return maxSingleFileBytes; }
    public void setMaxSingleFileBytes(long value) { this.maxSingleFileBytes = value; }
    public int getMaxWriteOperations() { return maxWriteOperations; }
    public void setMaxWriteOperations(int value) { this.maxWriteOperations = value; }
    public long getMaxWrittenBytes() { return maxWrittenBytes; }
    public void setMaxWrittenBytes(long value) { this.maxWrittenBytes = value; }
    public long getMaxDiffBytes() { return maxDiffBytes; }
    public void setMaxDiffBytes(long value) { this.maxDiffBytes = value; }
    public int getMaxTestRepairRounds() { return maxTestRepairRounds; }
    public void setMaxTestRepairRounds(int value) { this.maxTestRepairRounds = value; }

    Duration requiredRetention() {
        if (retention == null
                || retention.compareTo(Duration.ofHours(1)) < 0
                || retention.compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException(
                    "Coding Workspace retention must be between 1 hour and 365 days");
        }
        return retention;
    }
}
