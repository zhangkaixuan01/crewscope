package io.crewscope.application.coding;

import io.crewscope.domain.coding.BuildProfile;
import io.crewscope.domain.coding.BuildProfileReference;
import java.util.List;
import java.util.Optional;

/** Exact-version catalog Port; callers cannot silently fall forward to a newer profile. */
public interface BuildProfileCatalog {

    Optional<BuildProfile> findExact(BuildProfileReference reference);

    /** Deployment-approved options in stable presentation order. */
    default List<BuildProfile> findAll() {
        return List.of();
    }
}
