package io.crewscope.application.retrieval;

import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.List;
import java.util.Map;

/**
 * Read-side Port projecting the derived index status of knowledge entries: INDEXED when
 * a vector row exists for the effective revision, FAILED when the latest job failed,
 * PENDING otherwise. The batch form exists so entry listings never degrade into an
 * N+1 scan.
 */
public interface KnowledgeIndexStatusCatalog {

    KnowledgeIndexStatus statusOf(
            OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId);

    /** Statuses of many entries of one Team; entry ids absent from the answer are PENDING. */
    Map<KnowledgeEntryId, KnowledgeIndexStatus> statusesOf(
            OrganizationId organizationId, TeamId teamId, List<KnowledgeEntryId> entryIds);
}
