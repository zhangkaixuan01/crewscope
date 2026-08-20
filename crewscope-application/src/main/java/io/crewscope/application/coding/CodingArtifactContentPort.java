package io.crewscope.application.coding;

import io.crewscope.application.artifact.ArtifactAccessContext;
import io.crewscope.application.artifact.ArtifactByteRange;
import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.TestEvidence;
import java.util.Optional;

/** Integrity-checking content Port implemented by the governed ArtifactStore adapter. */
public interface CodingArtifactContentPort {

    CodingArtifactContent readPatch(
            DiffArtifact artifact,
            ArtifactAccessContext accessContext,
            Optional<ArtifactByteRange> range);

    CodingArtifactContent readBuildLog(
            CommandEvidence evidence,
            ArtifactAccessContext accessContext,
            Optional<ArtifactByteRange> range);

    CodingArtifactContent readTestReport(
            TestEvidence evidence,
            ArtifactAccessContext accessContext,
            Optional<ArtifactByteRange> range);
}
