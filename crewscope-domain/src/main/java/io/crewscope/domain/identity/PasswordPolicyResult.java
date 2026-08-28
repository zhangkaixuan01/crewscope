package io.crewscope.domain.identity;

/** Non-secret password policy decision; the submitted password is never retained. */
public enum PasswordPolicyResult {
    ACCEPTED,
    TOO_SHORT,
    TOO_LONG,
    TOO_LARGE,
    COMMON_PASSWORD,
    INVALID_ENCODING;

    public boolean isAccepted() {
        return this == ACCEPTED;
    }
}
