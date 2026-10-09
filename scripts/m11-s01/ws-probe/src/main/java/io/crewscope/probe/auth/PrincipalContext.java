package io.crewscope.probe.auth;

/**
 * The probe's stand-in for the product's session-resolved member identity.
 * Stored in the WebSession attribute {@link #SESSION_ATTRIBUTE} by the login
 * controller, verified by the handshake filter, and read back from the
 * WebSocket session attributes by the handler (Spring Framework 7 hands the
 * WebSession attribute map to HandshakeInfo), so the whole chain is
 * cookie -> Redis session -> attribute, exactly like the product's
 * session-reused WebSocket authentication.
 */
public record PrincipalContext(String organizationId, String teamId,
        String principalId, String displayName) implements java.io.Serializable {

    public static final String SESSION_ATTRIBUTE = "probe.principal";
}
