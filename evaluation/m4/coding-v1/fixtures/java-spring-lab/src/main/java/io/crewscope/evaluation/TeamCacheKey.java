package io.crewscope.evaluation;

/** Produces a cache key for one scoped WorkItem query. */
public final class TeamCacheKey {

  public String forWorkItem(String organizationId, String teamId, String workItemId) {
    return organizationId + ":" + workItemId;
  }
}
