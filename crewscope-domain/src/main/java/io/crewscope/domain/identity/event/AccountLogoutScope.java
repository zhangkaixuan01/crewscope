package io.crewscope.domain.identity.event;

/** Logout reach without exposing any server-side session identifier. */
public enum AccountLogoutScope {
    CURRENT_SESSION,
    ALL_SESSIONS
}
