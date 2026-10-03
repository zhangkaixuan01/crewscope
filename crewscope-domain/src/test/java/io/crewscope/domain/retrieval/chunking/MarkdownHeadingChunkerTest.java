package io.crewscope.domain.retrieval.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Heading-boundary semantics of the markdown chunker and its oversized re-split. */
class MarkdownHeadingChunkerTest {

    private final MarkdownHeadingChunker chunker =
            new MarkdownHeadingChunker(ChunkingPolicy.defaults());

    @Test
    void splitsAtHeadingBoundariesKeepingTheHeadingLine() {
        List<SourceChunk> chunks = chunker.chunk(
                "docs/guide.md", "# Title\nbody-one\n## Section\nbody-two\n# Next\nbody-three");

        assertEquals(3, chunks.size());
        assertEquals(1, chunks.get(0).startLine());
        assertEquals(2, chunks.get(0).endLine());
        assertEquals("# Title\nbody-one", chunks.get(0).content());
        assertEquals(3, chunks.get(1).startLine());
        assertEquals(4, chunks.get(1).endLine());
        assertEquals(5, chunks.get(2).startLine());
        assertEquals(6, chunks.get(2).endLine());
        assertEquals("markdown", chunks.get(0).language());
    }

    @Test
    void reSplitsAnOversizedSectionWithAbsoluteLineSpans() {
        // A deliberately small window forces the re-split path; the section is the first
        // one in the document, so absolute spans must still start at line 1.
        MarkdownHeadingChunker small = new MarkdownHeadingChunker(new ChunkingPolicy(
                4, 3, 1, 1, ChunkingPolicy.DEFAULT_MAX_FILE_BYTES, List.of()));
        StringBuilder section = new StringBuilder("# Long\n");
        for (int line = 0; line < 9; line++) {
            section.append("detail-").append(line).append('\n');
        }
        List<SourceChunk> chunks = small.chunk("docs/long.md", section.toString().stripTrailing());

        assertTrue(chunks.size() >= 2, "an oversized section must be re-split, not truncated");
        assertEquals(1, chunks.get(0).startLine(), "spans stay absolute to the document");
        assertEquals("# Long\ndetail-0\ndetail-1\ndetail-2", chunks.get(0).content());
        int lastEnd = 0;
        for (SourceChunk chunk : chunks) {
            assertTrue(chunk.endLine() > lastEnd, "windows advance monotonically");
            assertTrue(chunk.endLine() <= 10, "no chunk reaches past the section");
            lastEnd = chunk.endLine();
        }
        assertEquals(10, lastEnd, "the final window covers the last line");
    }

    @Test
    void preambleBeforeTheFirstHeadingIsItsOwnChunk() {
        List<SourceChunk> chunks = chunker.chunk(
                "docs/preface.md", "intro-one\nintro-two\n# Heading\nbody");

        assertEquals(2, chunks.size());
        assertEquals(1, chunks.get(0).startLine());
        assertEquals(2, chunks.get(0).endLine());
        assertEquals("intro-one\nintro-two", chunks.get(0).content());
        assertEquals(3, chunks.get(1).startLine());
    }
}
