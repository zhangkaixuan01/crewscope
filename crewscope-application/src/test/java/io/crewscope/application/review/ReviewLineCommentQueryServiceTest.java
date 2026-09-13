package io.crewscope.application.review;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.domain.coding.DiffGeneration;
import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.review.ReviewCommentAnchor;
import io.crewscope.domain.review.ReviewCommentSide;
import io.crewscope.domain.review.ReviewDiffHunk;
import io.crewscope.domain.task.RuntimeContentHash;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies OLD/NEW coordinates and exact hunk-header binding for line comments. */
class ReviewLineCommentQueryServiceTest {

    private static final String PATH = "src/App.java";
    private static final String HEADER = "@@ -40 +100 @@";
    private static final String PATCH = HEADER + "\n-old value\n+new value\n";

    @Test
    void resolvesOldAndNewCoordinatesAgainstTheirOwnDiffRanges() {
        ContextPackage context = mock(ContextPackage.class);
        when(context.diff()).thenReturn(mock(io.crewscope.domain.review.ReviewDiffReference.class));
        when(context.diff().generation()).thenReturn(DiffGeneration.first());
        when(context.hunks()).thenReturn(List.of(ReviewDiffHunk.captured(PATH, 100, 100, PATCH)));

        ReviewCommentAnchor oldAnchor = new ReviewCommentAnchor(
                new io.crewscope.domain.review.FindingLocation(new DiffPath(PATH), 40, 40),
                ReviewCommentSide.OLD, HEADER, RuntimeContentHash.sha256("old value"), DiffGeneration.first());
        ReviewCommentAnchor newAnchor = new ReviewCommentAnchor(
                new io.crewscope.domain.review.FindingLocation(new DiffPath(PATH), 100, 100),
                ReviewCommentSide.NEW, HEADER, RuntimeContentHash.sha256("new value"), DiffGeneration.first());

        assertTrue(ReviewLineCommentQueryService.isCurrent(oldAnchor, context));
        assertTrue(ReviewLineCommentQueryService.isCurrent(newAnchor, context));
    }

    @Test
    void rejectsHeaderOrLineHashDrift() {
        ContextPackage context = mock(ContextPackage.class);
        when(context.diff()).thenReturn(mock(io.crewscope.domain.review.ReviewDiffReference.class));
        when(context.diff().generation()).thenReturn(DiffGeneration.first());
        when(context.hunks()).thenReturn(List.of(ReviewDiffHunk.captured(PATH, 100, 100, PATCH)));
        ReviewCommentAnchor wrongHeader = new ReviewCommentAnchor(
                new io.crewscope.domain.review.FindingLocation(new DiffPath(PATH), 100, 100),
                ReviewCommentSide.NEW, "@@ -40 +100 @@ method", RuntimeContentHash.sha256("new value"), DiffGeneration.first());
        ReviewCommentAnchor wrongHash = new ReviewCommentAnchor(
                new io.crewscope.domain.review.FindingLocation(new DiffPath(PATH), 100, 100),
                ReviewCommentSide.NEW, HEADER, RuntimeContentHash.sha256("tampered"), DiffGeneration.first());

        assertFalse(ReviewLineCommentQueryService.isCurrent(wrongHeader, context));
        assertFalse(ReviewLineCommentQueryService.isCurrent(wrongHash, context));
    }
}
