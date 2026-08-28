package io.crewscope.application.team;

import io.crewscope.domain.team.InvitationTokenDigest;

/** Derives the fixed lookup digest persisted in place of an invitation bearer secret. */
@FunctionalInterface
public interface InvitationTokenDigester {

    InvitationTokenDigest digest(InvitationToken token);
}
