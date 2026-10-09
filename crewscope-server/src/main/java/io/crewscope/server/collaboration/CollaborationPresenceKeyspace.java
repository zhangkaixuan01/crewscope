package io.crewscope.server.collaboration;

import io.crewscope.domain.collaboration.CollaborationResourceScope;
import io.crewscope.domain.collaboration.ResourceScope;
import io.crewscope.domain.collaboration.TeamScope;
import io.crewscope.domain.collaboration.WorkProjectScope;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Redis keyspace for collaboration presence (ADR-032): one connection hash carrying the TTL
 * and one ZSET index per scope carrying the fanout set. Environment-isolated with the same
 * conventions as {@code CrewScopeRedisKeyspace} and {@code LoginDefenseKeyspace}, and
 * disjoint from the session, agent-state, and login-defense key spaces.
 */
public final class CollaborationPresenceKeyspace {

  /** Team-granularity ZSET segment: every signal of the team under one index. */
  public static final String TEAM_RESOURCE_TYPE = "team";
  public static final String TEAM_RESOURCE_ID = "all";
  public static final String WORK_PROJECT_RESOURCE_TYPE = "work_project";

  private static final Pattern ENVIRONMENT = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");

  private final String prefix;

  public CollaborationPresenceKeyspace(String environment) {
    String normalized = Objects.requireNonNull(environment, "environment").strip();
    if (!ENVIRONMENT.matcher(normalized).matches()) {
      throw new IllegalArgumentException(
          "Redis environment must use 1 to 32 lowercase letters, digits or hyphens");
    }
    this.prefix = "crewscope:" + normalized + ":collaboration:v1:";
  }

  /** {@code ...presence:conn:{connectionId}} — the hash whose TTL bounds the connection. */
  public String connectionKey(String connectionId) {
    return prefix + "presence:conn:" + connectionId;
  }

  /** {@code ...presence:scope:{org}:{team}:{resourceType}:{resourceId}} — the fanout ZSET. */
  public String scopeKey(CollaborationResourceScope scope) {
    return prefix
        + "presence:scope:"
        + scope.organizationId().value()
        + ':'
        + scope.teamId().value()
        + ':'
        + resourceTypeSegment(scope)
        + ':'
        + resourceIdSegment(scope);
  }

  /** SCAN pattern covering every scope ZSET, used by the periodic sweeper. */
  public String scopePattern() {
    return prefix + "presence:scope:*";
  }

  /** The resourceType segment of the scope key — also the conn-hash field of the same name. */
  public String resourceTypeSegment(CollaborationResourceScope scope) {
    if (scope instanceof TeamScope) {
      return TEAM_RESOURCE_TYPE;
    }
    if (scope instanceof WorkProjectScope) {
      return WORK_PROJECT_RESOURCE_TYPE;
    }
    if (scope instanceof ResourceScope resource) {
      return resource.type().name().toLowerCase(Locale.ROOT);
    }
    throw new IllegalArgumentException("unknown scope granularity: " + scope);
  }

  /** The resourceId segment of the scope key — also the conn-hash field of the same name. */
  public String resourceIdSegment(CollaborationResourceScope scope) {
    if (scope instanceof TeamScope) {
      return TEAM_RESOURCE_ID;
    }
    if (scope instanceof WorkProjectScope project) {
      return project.projectId().value().toString();
    }
    if (scope instanceof ResourceScope resource) {
      return resource.resourceId().toString();
    }
    throw new IllegalArgumentException("unknown scope granularity: " + scope);
  }
}
