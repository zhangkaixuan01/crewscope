package io.crewscope.infrastructure.security.invitation;

import io.crewscope.application.team.InvitationTokenDigester;
import io.crewscope.application.team.InvitationTokenGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Conditional production assembly requiring an external HMAC key before invitation APIs start. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "crewscope.invitation.token.enabled",
        havingValue = "true")
@EnableConfigurationProperties(InvitationTokenSecurityProperties.class)
public class InvitationTokenSecurityConfiguration {

    @Bean
    @ConditionalOnMissingBean(InvitationTokenGenerator.class)
    InvitationTokenGenerator invitationTokenGenerator() {
        return new SecureInvitationTokenGenerator();
    }

    @Bean
    @ConditionalOnMissingBean(InvitationTokenDigester.class)
    InvitationTokenDigester invitationTokenDigester(
            InvitationTokenSecurityProperties properties) {
        return new HmacSha256InvitationTokenDigester(properties.getHmacKey());
    }
}
