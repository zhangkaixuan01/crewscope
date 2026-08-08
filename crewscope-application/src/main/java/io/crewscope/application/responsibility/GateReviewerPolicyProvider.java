package io.crewscope.application.responsibility;

import io.crewscope.domain.responsibility.ReviewerEligibilityPolicy;
import io.crewscope.domain.workitem.WorkItem;

/** Resolves the trusted Team/WorkProject reviewer policy used by a Gate assignment command. */
@FunctionalInterface
public interface GateReviewerPolicyProvider {

  ReviewerEligibilityPolicy resolve(WorkItem workItem);
}
