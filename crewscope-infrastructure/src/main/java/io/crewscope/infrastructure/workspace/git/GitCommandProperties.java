package io.crewscope.infrastructure.workspace.git;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Type-safe deployment properties for the M4 host Git command boundary. */
@ConfigurationProperties("crewscope.coding.git")
public class GitCommandProperties {

    private String commandHome = "./var/crewscope/git-home";
    private Duration timeout = Duration.ofSeconds(30);
    private int maximumOutputBytes = 1024 * 1024;

    public String getCommandHome() {
        return commandHome;
    }

    public void setCommandHome(String commandHome) {
        this.commandHome = commandHome;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaximumOutputBytes() {
        return maximumOutputBytes;
    }

    public void setMaximumOutputBytes(int maximumOutputBytes) {
        this.maximumOutputBytes = maximumOutputBytes;
    }

    GitCommandPolicy toPolicy() {
        return new GitCommandPolicy(Path.of(commandHome), timeout, maximumOutputBytes);
    }
}
