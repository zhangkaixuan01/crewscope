package io.crewscope.server.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.crewscope.application.audit.AuditAccessRecorder;
import io.crewscope.application.audit.AuditAuthorization;
import io.crewscope.application.audit.AuditQueryApplicationService;
import io.crewscope.application.audit.AuditQueryPort;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.server.api.AuditCursorCodec;
import io.crewscope.server.api.TeamActivityCursorKeyRing;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Constructor-injection and conditional signed-Cursor composition proof for M6-A03. */
class AuditApplicationConfigurationM6A03Test {

    @Test
    void wiresAuthorizationSelfAuditAndQueryServiceExactlyOnce() {
        runner()
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AuditAuthorization.class);
                    assertThat(context).hasSingleBean(AuditQueryApplicationService.class);
                    assertThat(context).doesNotHaveBean(AuditCursorCodec.class);
                });
    }

    @Test
    void createsAuditCursorCodecOnlyWhenTheServerKeyRingExists() {
        runner()
                .withBean(
                        TeamActivityCursorKeyRing.class,
                        () -> new TeamActivityCursorKeyRing(
                                "k1",
                                Map.of(
                                        "k1",
                                        Base64.getEncoder()
                                                .encodeToString(new byte[32]))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AuditCursorCodec.class);
                });
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(AuditApplicationConfiguration.class)
                .withBean(AuditQueryPort.class, () -> mock(AuditQueryPort.class))
                .withBean(AuditAccessRecorder.class, () -> mock(AuditAccessRecorder.class))
                .withBean(TeamMembershipQuery.class, () -> mock(TeamMembershipQuery.class))
                .withBean(TeamRoleRepository.class, () -> mock(TeamRoleRepository.class))
                .withBean(MemberRoleRepository.class, () -> mock(MemberRoleRepository.class))
                .withBean(TimeProvider.class, () -> mock(TimeProvider.class));
    }
}
