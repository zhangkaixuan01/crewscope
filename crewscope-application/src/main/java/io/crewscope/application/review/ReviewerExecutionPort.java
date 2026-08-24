package io.crewscope.application.review;

import io.crewscope.domain.review.ReviewFindingCandidate;
import java.util.List;
import java.util.concurrent.CompletionStage;

/** Asynchronous Port for one no-Tool AgentScope Reviewer structured-output call. */
@FunctionalInterface
public interface ReviewerExecutionPort {

    CompletionStage<List<ReviewFindingCandidate>> execute(ReviewerExecutionCommand command);
}
