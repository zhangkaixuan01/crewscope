package io.crewscope.application.skill;

import io.crewscope.application.distiller.DistillerReadiness;

/**
 * Readiness seam for the built-in Skill Distiller (M10-A03b); the shared contract lives in
 * {@link DistillerReadiness}.
 */
@FunctionalInterface
public interface SkillDistillerReadiness extends DistillerReadiness {}
