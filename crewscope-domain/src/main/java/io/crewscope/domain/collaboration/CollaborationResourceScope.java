package io.crewscope.domain.collaboration;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;

/**
 * Stable coordinate of a collaboration subscription (ADR-032): organization and team plus
 * one of the three granularities — the whole Team ({@link TeamScope}), one WorkProject
 * ({@link WorkProjectScope}), or a single resource ({@link ResourceScope}). The organization
 * is part of the coordinate because every durable authorization port resolves membership per
 * organization; unparseable or absent identifiers are rejected at the frame boundary before
 * a scope value ever exists.
 */
public sealed interface CollaborationResourceScope
    permits TeamScope, WorkProjectScope, ResourceScope {

  OrganizationId organizationId();

  TeamId teamId();
}
