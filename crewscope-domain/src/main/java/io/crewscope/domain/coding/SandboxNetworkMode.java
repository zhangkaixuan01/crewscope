package io.crewscope.domain.coding;

/** Ordered network authority from most restrictive to broadest. */
public enum SandboxNetworkMode {
    NONE,
    LOOPBACK_ONLY,
    RESTRICTED_EGRESS;

    public boolean isNoBroaderThan(SandboxNetworkMode baseline) {
        return ordinal() <= java.util.Objects.requireNonNull(baseline, "baseline").ordinal();
    }
}
