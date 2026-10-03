package io.crewscope.domain.retrieval.chunking;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Chunker for markdown documents (M10-S01 §3.3): sections split at ATX heading
 * boundaries, each section keeping its heading line; a section longer than the policy
 * window is re-split with the same line-window chunker so no document is ever silently
 * truncated. What fits one window stays one chunk — document sections are semantic units
 * first and windows second.
 */
public final class MarkdownHeadingChunker {

    private static final Pattern ATX_HEADING = Pattern.compile("^#{1,6}(?:\\s.*)?$");

    public static final String LANGUAGE = "markdown";

    private final ChunkingPolicy policy;
    private final CodeLineChunker lineChunker;

    public MarkdownHeadingChunker(ChunkingPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.lineChunker = new CodeLineChunker(policy);
    }

    public List<SourceChunk> chunk(String path, String content) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");
        String[] lines = content.split("\n", -1);
        List<SourceChunk> chunks = new ArrayList<>();
        int sectionStart = 0;
        for (int index = 0; index <= lines.length; index++) {
            boolean boundary = index == lines.length
                    || (index > 0 && ATX_HEADING.matcher(lines[index]).matches());
            if (!boundary) {
                continue;
            }
            if (index > sectionStart) {
                chunks.addAll(section(path, lines, sectionStart, index));
            }
            sectionStart = index;
        }
        return chunks;
    }

    private List<SourceChunk> section(String path, String[] lines, int start, int end) {
        String content = String.join("\n", Arrays.asList(lines).subList(start, end));
        if (content.isBlank()) {
            return List.of();
        }
        if (end - start <= policy.windowLines()) {
            return List.of(SourceChunk.of(path, LANGUAGE, start + 1, end, content));
        }
        // Oversized section: re-split by the line window, preserving every line exactly
        // once and reporting absolute document line spans.
        return lineChunker.chunk(path, LANGUAGE, content, start);
    }
}
