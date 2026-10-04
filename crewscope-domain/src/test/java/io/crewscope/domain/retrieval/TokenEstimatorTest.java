package io.crewscope.domain.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The frozen estimate formula (M10-S01 §3.4): max(ceil(utf8Bytes/4),
 * ceil(codePoints/3)), floored at 1. The budget arithmetic downstream of these
 * numbers is only as honest as this estimator, so the halves are pinned per script.
 */
final class TokenEstimatorTest {

    @Test
    void emptyTextStillCostsOneToken() {
        assertEquals(1, TokenEstimator.estimate(""));
    }

    @Test
    void pureAsciiRidesTheCodePointHalf() {
        // ceil(7/3) = 3 dominates ceil(7/4) = 2.
        assertEquals(3, TokenEstimator.estimate("abcdefg"));
        assertEquals(1, TokenEstimator.estimate("abc"));
    }

    @Test
    void cjkCountsOneTokenPerCharacter() {
        // 3 characters, 9 UTF-8 bytes: the byte half gives ceil(9/4) = 3.
        assertEquals(3, TokenEstimator.estimate("知识库"));
        assertEquals(1, TokenEstimator.estimate("知"));
    }

    @Test
    void surrogatePairsCountAsOneCodePointEach() {
        assertEquals(1, TokenEstimator.estimate("😀"));
        // 8 UTF-8 bytes, 2 code points: the byte half wins with 2.
        assertEquals(2, TokenEstimator.estimate("😀😀"));
    }

    @Test
    void takesTheLargerHalf() {
        // 6 UTF-8 bytes → ceil(6/4) = 2 beats 2 code points → ceil(2/3) = 1.
        assertEquals(2, TokenEstimator.estimate("知识"));
    }

    @Test
    void estimateAllSumsAcrossTexts() {
        assertEquals(2 + 1 + 3, TokenEstimator.estimateAll(List.of("知识", "abc", "abcdefg")));
        assertEquals(0, TokenEstimator.estimateAll(List.of()));
        assertEquals(1, TokenEstimator.estimateAll(List.of("")));
    }
}
