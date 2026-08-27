package io.crewscope.application.correlation;

/** Constant-query-count persistence boundary for correlation history and object enrichment. */
public interface CorrelationQueryPort {

    CorrelationPage find(CorrelationQuery query);
}
