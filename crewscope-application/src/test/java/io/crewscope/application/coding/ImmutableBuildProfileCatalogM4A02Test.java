package io.crewscope.application.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.coding.BuildCommand;
import io.crewscope.domain.coding.BuildProfile;
import io.crewscope.domain.coding.BuildProfileReference;
import io.crewscope.domain.coding.BuildTool;
import io.crewscope.domain.coding.CommandCatalog;
import io.crewscope.domain.coding.CommandKind;
import io.crewscope.domain.coding.SandboxImageReference;
import io.crewscope.domain.task.TaskFactHash;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Exact-version catalog contract used while capturing M4-A02 Coding targets. */
class ImmutableBuildProfileCatalogM4A02Test {

    @Test
    void resolvesOnlyTheExactKeyVersionAndCanonicalHash() {
        BuildProfile profile = profile("a");
        ImmutableBuildProfileCatalog catalog =
                new ImmutableBuildProfileCatalog(List.of(profile));

        assertEquals(profile, catalog.findExact(profile.reference()).orElseThrow());
        assertTrue(catalog.findExact(new BuildProfileReference(
                        profile.key(), profile.version(), TaskFactHash.sha256("different")))
                .isEmpty());
    }

    @Test
    void rejectsTwoDifferentFactsClaimingTheSameKeyAndVersion() {
        assertThrows(IllegalArgumentException.class, () -> new ImmutableBuildProfileCatalog(
                List.of(profile("a"), profile("b"))));
    }

    private static BuildProfile profile(String digestCharacter) {
        return BuildProfile.define(
                "maven-java-17",
                1,
                BuildTool.MAVEN,
                17,
                new SandboxImageReference(
                        "maven@sha256:" + digestCharacter.repeat(64)),
                CommandCatalog.of(
                        CommandKind.TEST,
                        new BuildCommand(
                                "coding.maven.test",
                                List.of("mvn", "test"),
                                ".",
                                60,
                                900)));
    }
}
