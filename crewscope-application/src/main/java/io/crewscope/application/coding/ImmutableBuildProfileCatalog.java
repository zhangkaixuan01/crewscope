package io.crewscope.application.coding;

import io.crewscope.domain.coding.BuildProfile;
import io.crewscope.domain.coding.BuildProfileReference;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Process-local catalog of deployment-approved immutable BuildProfile versions. */
public final class ImmutableBuildProfileCatalog implements BuildProfileCatalog {

    private final Map<BuildProfileReference, BuildProfile> profiles;

    public ImmutableBuildProfileCatalog(Collection<BuildProfile> profiles) {
        Collection<BuildProfile> supplied = Objects.requireNonNull(profiles, "profiles");
        Map<String, BuildProfileReference> versions = new HashMap<>();
        Map<BuildProfileReference, BuildProfile> indexed = new HashMap<>();
        for (BuildProfile profile : supplied) {
            BuildProfile required = Objects.requireNonNull(profile, "buildProfile");
            BuildProfileReference reference = required.reference();
            String versionKey = reference.key() + "\u0000" + reference.version();
            BuildProfileReference previousVersion = versions.putIfAbsent(versionKey, reference);
            if (previousVersion != null) {
                throw new IllegalArgumentException(
                        "BuildProfile key and version must identify exactly one immutable profile");
            }
            indexed.put(reference, required);
        }
        this.profiles = Map.copyOf(indexed);
    }

    @Override
    public Optional<BuildProfile> findExact(BuildProfileReference reference) {
        return Optional.ofNullable(profiles.get(Objects.requireNonNull(reference, "reference")));
    }

    @Override
    public List<BuildProfile> findAll() {
        return profiles.values().stream()
                .sorted(java.util.Comparator.comparing(BuildProfile::key)
                        .thenComparingLong(BuildProfile::version))
                .toList();
    }
}
