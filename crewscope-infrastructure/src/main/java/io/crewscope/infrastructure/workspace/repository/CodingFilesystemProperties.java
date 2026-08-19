package io.crewscope.infrastructure.workspace.repository;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Parser ceilings applied in addition to each WorkspaceOperationBudget. */
@ConfigurationProperties("crewscope.coding.filesystem")
public class CodingFilesystemProperties {

    private int maxToolContentBytes = 1_048_576;
    private int maxPatchHunks = 200;

    public int getMaxToolContentBytes() {
        return maxToolContentBytes;
    }

    public void setMaxToolContentBytes(int maxToolContentBytes) {
        this.maxToolContentBytes = maxToolContentBytes;
    }

    public int getMaxPatchHunks() {
        return maxPatchHunks;
    }

    public void setMaxPatchHunks(int maxPatchHunks) {
        this.maxPatchHunks = maxPatchHunks;
    }

    int requiredMaxToolContentBytes() {
        return bounded(maxToolContentBytes, 1_024, 16 * 1_024 * 1_024, "Filesystem tool content bytes");
    }

    int requiredMaxPatchHunks() {
        return bounded(maxPatchHunks, 1, 2_000, "Filesystem patch hunks");
    }

    private static int bounded(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }
}
