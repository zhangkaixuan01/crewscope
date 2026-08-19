package io.crewscope.infrastructure.workspace.repository;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded result and traversal settings for AgentScope repository inspection tools. */
@ConfigurationProperties("crewscope.coding.inspection")
public class RepositoryInspectionProperties {

    private int maxPageSize = 200;
    private int maxReadLines = 500;
    private int maxTreeDepth = 6;
    private int maxBackendOperations = 64;
    private int maxPatternLength = 256;
    private int maxResultBytes = 65_536;

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }

    public int getMaxReadLines() {
        return maxReadLines;
    }

    public void setMaxReadLines(int maxReadLines) {
        this.maxReadLines = maxReadLines;
    }

    public int getMaxTreeDepth() {
        return maxTreeDepth;
    }

    public void setMaxTreeDepth(int maxTreeDepth) {
        this.maxTreeDepth = maxTreeDepth;
    }

    public int getMaxBackendOperations() {
        return maxBackendOperations;
    }

    public void setMaxBackendOperations(int maxBackendOperations) {
        this.maxBackendOperations = maxBackendOperations;
    }

    public int getMaxPatternLength() {
        return maxPatternLength;
    }

    public void setMaxPatternLength(int maxPatternLength) {
        this.maxPatternLength = maxPatternLength;
    }

    public int getMaxResultBytes() {
        return maxResultBytes;
    }

    public void setMaxResultBytes(int maxResultBytes) {
        this.maxResultBytes = maxResultBytes;
    }

    int requiredMaxPageSize() {
        return bounded(maxPageSize, 1, 1_000, "Inspection page size");
    }

    int requiredMaxReadLines() {
        return bounded(maxReadLines, 1, 5_000, "Inspection read lines");
    }

    int requiredMaxTreeDepth() {
        return bounded(maxTreeDepth, 1, 20, "Inspection tree depth");
    }

    int requiredMaxBackendOperations() {
        return bounded(maxBackendOperations, 1, 1_000, "Inspection backend operations");
    }

    int requiredMaxPatternLength() {
        return bounded(maxPatternLength, 1, 4_096, "Inspection pattern length");
    }

    int requiredMaxResultBytes() {
        return bounded(maxResultBytes, 1_024, 4 * 1_024 * 1_024, "Inspection result bytes");
    }

    private static int bounded(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }
}
