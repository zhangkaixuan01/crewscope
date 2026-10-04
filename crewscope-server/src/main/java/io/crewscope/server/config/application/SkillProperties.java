package io.crewscope.server.config.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Write-side switch for the Team Skill catalog (M10-A03a). The default {@code false}
 * means "do not author or execute new Team Skills": every write command answers
 * 422 {@code skill_disabled} while the read surfaces stay available, so historical
 * catalog entries and evidence remain viewable under authorization.
 */
@ConfigurationProperties(prefix = "crewscope.skill")
public class SkillProperties {

    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
