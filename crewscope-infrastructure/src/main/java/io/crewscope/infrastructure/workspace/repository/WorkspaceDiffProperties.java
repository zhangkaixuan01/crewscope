package io.crewscope.infrastructure.workspace.repository;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment limits for WatchService hints, Patch previews and bounded replay. */
@ConfigurationProperties("crewscope.coding.diff")
public class WorkspaceDiffProperties {

    private int patchPreviewBytes = 64 * 1024;
    private int patchPreviewLines = 1_000;
    private int retainedEvents = 512;
    private int maximumReplayEvents = 256;
    private int maximumEventBytes = 512 * 1024;
    private Duration debounce = Duration.ofMillis(150);
    private Duration reconcileInterval = Duration.ofSeconds(15);
    private String cursorSecret = "crewscope-development-diff-cursor-key-v1";

    public int getPatchPreviewBytes() {
        return patchPreviewBytes;
    }

    public void setPatchPreviewBytes(int patchPreviewBytes) {
        this.patchPreviewBytes = patchPreviewBytes;
    }

    public int getPatchPreviewLines() {
        return patchPreviewLines;
    }

    public void setPatchPreviewLines(int patchPreviewLines) {
        this.patchPreviewLines = patchPreviewLines;
    }

    public int getRetainedEvents() {
        return retainedEvents;
    }

    public void setRetainedEvents(int retainedEvents) {
        this.retainedEvents = retainedEvents;
    }

    public int getMaximumReplayEvents() {
        return maximumReplayEvents;
    }

    public void setMaximumReplayEvents(int maximumReplayEvents) {
        this.maximumReplayEvents = maximumReplayEvents;
    }

    public int getMaximumEventBytes() {
        return maximumEventBytes;
    }

    public void setMaximumEventBytes(int maximumEventBytes) {
        this.maximumEventBytes = maximumEventBytes;
    }

    public Duration getDebounce() {
        return debounce;
    }

    public void setDebounce(Duration debounce) {
        this.debounce = debounce;
    }

    public Duration getReconcileInterval() {
        return reconcileInterval;
    }

    public void setReconcileInterval(Duration reconcileInterval) {
        this.reconcileInterval = reconcileInterval;
    }

    public String getCursorSecret() {
        return cursorSecret;
    }

    public void setCursorSecret(String cursorSecret) {
        this.cursorSecret = cursorSecret;
    }

    void validate() {
        if (patchPreviewBytes < 1
                || patchPreviewBytes > io.crewscope.domain.coding.DiffFileEntry.MAX_PREVIEW_BYTES
                || patchPreviewLines < 1
                || patchPreviewLines > io.crewscope.domain.coding.DiffFileEntry.MAX_PREVIEW_LINES) {
            throw new IllegalArgumentException("Diff Patch preview limits are outside domain bounds");
        }
        if (retainedEvents < 2 || retainedEvents > 10_000) {
            throw new IllegalArgumentException("Diff retained events must be between 2 and 10000");
        }
        if (maximumReplayEvents < 1 || maximumReplayEvents > retainedEvents) {
            throw new IllegalArgumentException("Diff replay limit must fit retained events");
        }
        if (maximumEventBytes < 4 * 1024 || maximumEventBytes > 4 * 1024 * 1024) {
            throw new IllegalArgumentException("Diff event limit must be between 4 KiB and 4 MiB");
        }
        requireDuration(debounce, Duration.ofMillis(10), Duration.ofSeconds(5), "debounce");
        requireDuration(
                reconcileInterval, Duration.ofSeconds(1), Duration.ofMinutes(10), "interval");
        if (cursorSecret == null
                || cursorSecret.getBytes(StandardCharsets.UTF_8).length < 32
                || cursorSecret.length() > 1_024
                || cursorSecret.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "Diff cursor secret must contain at least 32 UTF-8 bytes");
        }
    }

    byte[] cursorSecretBytes() {
        validate();
        return cursorSecret.getBytes(StandardCharsets.UTF_8);
    }

    private static void requireDuration(
            Duration value, Duration minimum, Duration maximum, String name) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Diff " + name + " is outside its supported range");
        }
    }
}
