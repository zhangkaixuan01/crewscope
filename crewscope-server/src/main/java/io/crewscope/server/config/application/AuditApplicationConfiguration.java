package io.crewscope.server.config.application;

import io.crewscope.application.audit.AuditAccessRecorder;
import io.crewscope.application.audit.AuditAuthorization;
import io.crewscope.application.audit.AuditQueryApplicationService;
import io.crewscope.application.audit.AuditQueryPort;
import io.crewscope.application.audit.DefaultAuditAuthorization;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.server.api.AuditCursorCodec;
import io.crewscope.server.api.TeamActivityCursorKeyRing;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires current-permission Audit queries, self-auditing and signed Cursor transport. */
@Configuration(proxyBeanMethods = false)
public class AuditApplicationConfiguration {

    @Bean
    AuditAuthorization auditAuthorization(
            TeamMembershipQuery memberships,
            TeamRoleRepository roles,
            MemberRoleRepository grants) {
        return new DefaultAuditAuthorization(memberships, roles, grants);
    }

    @Bean
    AuditQueryApplicationService auditQueryApplicationService(
            AuditQueryPort queries,
            AuditAuthorization authorization,
            AuditAccessRecorder accessRecorder,
            TimeProvider timeProvider) {
        return new AuditQueryApplicationService(
                queries, authorization, accessRecorder, timeProvider);
    }

    @Bean
    @ConditionalOnBean(TeamActivityCursorKeyRing.class)
    AuditCursorCodec auditCursorCodec(TeamActivityCursorKeyRing keyRing) {
        return new AuditCursorCodec(keyRing);
    }
}
