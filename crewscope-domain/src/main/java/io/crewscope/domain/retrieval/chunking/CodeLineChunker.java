package io.crewscope.domain.retrieval.chunking;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Line-window chunker for code files (M10-S01 §3.3), a faithful port of the S01b-measured
 * prototype: an ~80-line window advancing in ~70-line steps, snapping the cut back to a
 * blank line inside the ~10-line overlap (never earlier than {@code snapFloor} lines into
 * the window), and skipping windows that contain only whitespace. Line spans are 1-based
 * and inclusive; the last window may be short but is never truncated mid-content. A
 * trailing newline splits into one trailing empty element, so a file ending in a newline
 * reports one line beyond its visible content — the prototype's counting convention.
 */
public final class CodeLineChunker {

    private final ChunkingPolicy policy;

    public CodeLineChunker(ChunkingPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public List<SourceChunk> chunk(String path, String language, String content) {
        return chunk(path, language, content, 0);
    }

    /**
     * Same window walk with a document line offset applied, so re-splitting one
     * oversized section still reports absolute document line spans.
     */
    List<SourceChunk> chunk(String path, String language, String content, int lineOffset) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(content, "content");
        String[] lines = content.split("\n", -1);
        List<SourceChunk> chunks = new ArrayList<>();
        for (int start = 0; start < lines.length; start += policy.stepLines()) {
            int end = Math.min(lines.length, start + policy.windowLines());
            if (end < lines.length) {
                // Snap to a blank line within the overlap, but never before the floor:
                // a floor keeps each window's identity stable across policies.
                for (int candidate = end;
                        candidate > end - policy.snapSearchLines()
                                && candidate > start + policy.snapFloorLines();
                        candidate--) {
                    if (lines[candidate - 1].isBlank()) {
                        end = candidate;
                        break;
                    }
                }
            }
            String window = String.join("\n", Arrays.asList(lines)
                    .subList(start, end));
            if (!window.isBlank()) {
                chunks.add(SourceChunk.of(
                        path, language, start + 1 + lineOffset, end + lineOffset, window));
            }
            if (end >= lines.length) {
                break;
            }
        }
        return chunks;
    }
}
