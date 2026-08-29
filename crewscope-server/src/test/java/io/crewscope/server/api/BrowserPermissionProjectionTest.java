package io.crewscope.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.crewscope.domain.identity.PlatformRole;
import io.crewscope.domain.team.TeamPermission;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Locks the Session-to-browser permission vocabulary used by the production Web application. */
class BrowserPermissionProjectionTest {

  @Test
  void projectsEveryBrowserCapabilityFromCurrentDomainFacts() {
    assertThat(BrowserPermissionProjection.team(EnumSet.allOf(TeamPermission.class)))
        .containsExactly(
            "agent:manage",
            "audit:read",
            "conversation:use",
            "governance:export",
            "provider:manage",
            "repositories:manage",
            "responsibility:manage",
            "scope:read",
            "team:members:manage",
            "team:members:read",
            "work-projects:manage",
            "work-projects:read",
            "work:create",
            "work:participate",
            "work:read");
  }

  @Test
  void keepsOrdinaryMemberAndPlatformOperatorCapabilitiesSeparate() {
    List<String> member = BrowserPermissionProjection.team(EnumSet.of(
        TeamPermission.WORK_CREATE,
        TeamPermission.WORK_PARTICIPATE,
        TeamPermission.COLLABORATION_REQUEST));

    assertThat(member)
        .contains("conversation:use", "scope:read", "team:members:read", "work:read")
        .doesNotContain("team:members:manage", "audit:read", "operations:manage");
    assertThat(BrowserPermissionProjection.account(PlatformRole.USER))
        .isEmpty();
    assertThat(BrowserPermissionProjection.account(PlatformRole.OPERATOR))
        .containsExactly("operations:manage");
  }
}
