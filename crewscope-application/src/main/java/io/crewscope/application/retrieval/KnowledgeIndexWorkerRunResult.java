package io.crewscope.application.retrieval;

/** One worker pass: how many jobs were claimed, ended FAILED, and hit a fence. */
public record KnowledgeIndexWorkerRunResult(
        int claimedJobs,
        int failedJobs,
        int fencedWrites) {

    public KnowledgeIndexWorkerRunResult {
        if (claimedJobs < 0 || failedJobs < 0 || fencedWrites < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
        if (failedJobs > claimedJobs) {
            throw new IllegalArgumentException("failedJobs cannot exceed claimedJobs");
        }
    }
}
