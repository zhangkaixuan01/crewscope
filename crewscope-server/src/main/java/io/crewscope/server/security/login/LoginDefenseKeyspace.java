package io.crewscope.server.security.login;

import java.util.Locale;
import java.util.Objects;

/** Cluster-slot-safe Redis keyspace containing only HMAC digests. */
final class LoginDefenseKeyspace {

    private final String prefix;

    LoginDefenseKeyspace(String environment) {
        String required = Objects.requireNonNull(environment, "environment")
                .strip()
                .toLowerCase(Locale.ROOT);
        if (!required.matches("[a-z0-9][a-z0-9_-]{1,31}")) {
            throw new IllegalStateException("login defense environment is invalid");
        }
        this.prefix = "crewscope:" + required + ":security:{login-defense}:v1:";
    }

    String identifier(String flow, String digest) {
        return prefix + flow + ":identifier:" + digest;
    }

    String network(String flow, String digest) {
        return prefix + flow + ":network:" + digest;
    }

    String accountFailures(String digest) {
        return prefix + "account:failures:" + digest;
    }

    String accountLock(String digest) {
        return prefix + "account:lock:" + digest;
    }

    String accountObserved(String digest) {
        return prefix + "account:observed:" + digest;
    }
}
