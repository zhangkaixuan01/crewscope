package io.crewscope.infrastructure.workspace.repository;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Agent-visible preview ceiling; the complete bounded output remains in ArtifactStore. */
@ConfigurationProperties("crewscope.coding.command")
public class SandboxCommandProperties {

    private int maxToolResultBytes = 65_536;

    public int getMaxToolResultBytes() {
        return maxToolResultBytes;
    }

    public void setMaxToolResultBytes(int maxToolResultBytes) {
        this.maxToolResultBytes = maxToolResultBytes;
    }

    int requiredMaxToolResultBytes() {
        if (maxToolResultBytes < 1_024 || maxToolResultBytes > 1_048_576) {
            throw new IllegalArgumentException(
                    "Command tool result bytes must be between 1024 and 1048576");
        }
        return maxToolResultBytes;
    }
}
