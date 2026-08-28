package io.crewscope.application.team;

/** Generates a fresh cryptographic bearer secret for one invitation issuance. */
@FunctionalInterface
public interface InvitationTokenGenerator {

    InvitationToken generate();
}
