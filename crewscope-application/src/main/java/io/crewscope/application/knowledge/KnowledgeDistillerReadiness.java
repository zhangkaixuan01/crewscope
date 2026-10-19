package io.crewscope.application.knowledge;

import io.crewscope.application.distiller.DistillerReadiness;

/**
 * Readiness seam for the built-in Knowledge Distiller (D10); the shared contract lives in
 * {@link DistillerReadiness}.
 */
@FunctionalInterface
public interface KnowledgeDistillerReadiness extends DistillerReadiness {}
