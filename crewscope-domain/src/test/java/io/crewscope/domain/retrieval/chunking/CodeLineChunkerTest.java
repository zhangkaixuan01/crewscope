package io.crewscope.domain.retrieval.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Window/step/snap semantics of the code line chunker, ported from the S01b prototype. */
class CodeLineChunkerTest {

    private final CodeLineChunker chunker = new CodeLineChunker(ChunkingPolicy.defaults());

    @Test
    void chunksHundredLinesIntoTwoOverlappingWindows() {
        List<SourceChunk> chunks = chunker.chunk("src/Main.java", "java", lines(100));

        assertEquals(2, chunks.size());
        assertEquals(1, chunks.get(0).startLine());
        assertEquals(80, chunks.get(0).endLine());
        assertEquals(71, chunks.get(1).startLine());
        assertEquals(100, chunks.get(1).endLine());
    }

    @Test
    void snapsToABlankLineInsideTheOverlap() {
        String content = numberedWithBlanks(100, java.util.Set.of(75));

        List<SourceChunk> chunks = chunker.chunk("src/Main.java", "java", content);

        assertEquals(75, chunks.get(0).endLine(), "the cut snaps back to the blank line");
        assertEquals(71, chunks.get(1).startLine(), "the step still advances from 70");
        assertEquals(100, chunks.get(1).endLine());
    }

    @Test
    void neverSnapsBeforeTheFloor() {
        // Overlap search spans candidates (end-snapSearch, end]; the floor forbids anything
        // at or below start+snapFloor, so the blank line at 30 must be ignored here.
        ChunkingPolicy wideOverlap = new ChunkingPolicy(
                80, 25, 50, 20, ChunkingPolicy.DEFAULT_MAX_FILE_BYTES, List.of());
        CodeLineChunker wide = new CodeLineChunker(wideOverlap);
        String content = numberedWithBlanks(100, java.util.Set.of(30));

        List<SourceChunk> chunks = wide.chunk("src/Main.java", "java", content);

        assertEquals(80, chunks.get(0).endLine(), "floor-blocked snap must not shorten the window");

        String contentWithReachableBlank = numberedWithBlanks(100, java.util.Set.of(35));
        assertEquals(35, wide.chunk("src/Main.java", "java", contentWithReachableBlank)
                .get(0).endLine(), "a blank inside the searched range still snaps");
    }

    @Test
    void skipsWindowsThatArePureWhitespace() {
        // Window policy(6,3,3,2): lines 4-9 blank, 1-3 and 10 non-blank. The [4,9] window
        // is entirely whitespace and produces no chunk; its neighbours still do.
        ChunkingPolicy small = new ChunkingPolicy(6, 3, 3, 2, ChunkingPolicy.DEFAULT_MAX_FILE_BYTES, List.of());
        StringBuilder content = new StringBuilder();
        for (int line = 1; line <= 10; line++) {
            if (line >= 4 && line <= 9) {
                content.append("   ");
            } else {
                content.append("line-").append(line);
            }
            if (line < 10) {
                content.append('\n');
            }
        }
        List<SourceChunk> chunks = new CodeLineChunker(small)
                .chunk("src/Main.java", "java", content.toString());

        assertEquals(List.of(1, 7), chunks.stream().map(SourceChunk::startLine).toList(),
                "the all-blank window is skipped");
        assertEquals(10, chunks.get(1).endLine());
    }

    @Test
    void singleChunkWhenTheFileFitsOneWindow() {
        List<SourceChunk> chunks = chunker.chunk("src/Tiny.java", "java", lines(10));

        assertEquals(1, chunks.size());
        assertEquals(1, chunks.get(0).startLine());
        assertEquals(10, chunks.get(0).endLine());
    }

    @Test
    void contentHashIsSha256OfTheChunkText() throws Exception {
        List<SourceChunk> chunks = chunker.chunk("src/Main.java", "java", lines(10));

        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(chunks.get(0).content().getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, chunks.get(0).contentHash());
    }

    // ------------------------------------------------------------------ fixtures

    private static String lines(int count) {
        return numberedWithBlanks(count, java.util.Set.of());
    }

    private static String numberedWithBlanks(int count, java.util.Set<Integer> blankLines) {
        StringBuilder content = new StringBuilder();
        for (int line = 1; line <= count; line++) {
            if (!blankLines.contains(line)) {
                content.append("line-").append(line);
            }
            if (line < count) {
                content.append('\n');
            }
        }
        return content.toString();
    }
}
