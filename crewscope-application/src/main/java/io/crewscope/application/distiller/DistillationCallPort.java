package io.crewscope.application.distiller;

import java.util.concurrent.CompletionStage;

/**
 * Parameterized asynchronous call boundary shared by the Knowledge and Skill distillation
 * ports: one no-Tool AgentScope structured-output call whose adapter resolves the Team's
 * built-in Distiller profile through the full governance chain before the call and reports
 * per-attempt token usage afterwards.
 */
@FunctionalInterface
public interface DistillationCallPort<REQUEST, RESULT> {

    CompletionStage<RESULT> distill(REQUEST request);
}
