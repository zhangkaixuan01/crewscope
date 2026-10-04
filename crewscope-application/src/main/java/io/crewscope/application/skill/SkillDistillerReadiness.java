package io.crewscope.application.skill;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;

/**
 * Lazy execution-time readiness boundary for the built-in Skill Distiller (M10-A03b):
 * guaranteed by the provisioning service on the first distillation of a Team, never
 * at startup. Readiness is idempotent and its return value carries no caller-facing
 * signal, mirroring the Knowledge Distiller seam.
 */
@FunctionalInterface
public interface SkillDistillerReadiness {

    void ensureReady(OrganizationId organizationId, TeamId teamId);
}
