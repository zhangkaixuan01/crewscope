package io.crewscope.infrastructure.security.invitation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** External secret used only for purpose-separated invitation token HMAC derivation. */
@ConfigurationProperties("crewscope.invitation.token")
public class InvitationTokenSecurityProperties {

    private String hmacKey = "";

    public String getHmacKey() {
        return hmacKey;
    }

    public void setHmacKey(String hmacKey) {
        this.hmacKey = hmacKey;
    }
}
