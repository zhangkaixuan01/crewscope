package io.crewscope.application.retrieval;

/** One worker pass: how many jobs were claimed, ended FAILED, hit a fence, and how many
 * repository files were skipped as un-embeddable (oversized chunks) across those jobs. */
public record KnowledgeIndexWorkerRunResult(
        int claimedJobs,
        int failedJobs,
        int fencedWrites,
        int skippedFiles) {

    /** Shape for passes with no skipped files (every path except repository builds). */
    public KnowledgeIndexWorkerRunResult(int claimedJobs, int failedJobs, int fencedWrites) {
        this(claimedJobs, failedJobs, fencedWrites, 0);
    }

    public KnowledgeIndexWorkerRunResult {
        if (claimedJobs < 0 || failedJobs < 0 || fencedWrites < 0 || skippedFiles < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
        if (failedJobs > claimedJobs) {
            throw new IllegalArgumentException("failedJobs cannot exceed claimedJobs");
        }
    }
}
