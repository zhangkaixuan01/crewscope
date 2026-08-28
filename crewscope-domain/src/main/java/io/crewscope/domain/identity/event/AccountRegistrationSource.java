package io.crewscope.domain.identity.event;

/** Trusted source that created one platform account without carrying submitted identity values. */
public enum AccountRegistrationSource {
    OPEN,
    INVITATION,
    BOOTSTRAP
}
