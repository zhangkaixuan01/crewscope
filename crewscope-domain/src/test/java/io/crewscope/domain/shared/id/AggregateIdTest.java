package io.crewscope.domain.shared.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AggregateIdTest {

    private static final String CANONICAL_UUID = "01989ee2-f6b0-7cda-97c4-1b337043d33f";

    @Test
    void generatesNonNilIdentifiersForEveryAggregateType() {
        List<AggregateId> identifiers = List.of(
                OrganizationId.generate(),
                TeamId.generate(),
                WorkspaceId.generate(),
                PrincipalId.generate(),
                ArtifactId.generate(),
                CredentialId.generate(),
                WorkProjectId.generate(),
                WorkItemId.generate());

        identifiers.forEach(identifier -> assertNotEquals(AggregateId.NIL_UUID, identifier.value()));
    }

    @Test
    void parsesAndPrintsOneCanonicalPersistenceRepresentation() {
        AggregateId organizationId = OrganizationId.from(CANONICAL_UUID);
        AggregateId teamId = TeamId.from(CANONICAL_UUID);
        AggregateId workspaceId = WorkspaceId.from(CANONICAL_UUID);
        AggregateId principalId = PrincipalId.from(CANONICAL_UUID);
        AggregateId artifactId = ArtifactId.from(CANONICAL_UUID);
        AggregateId credentialId = CredentialId.from(CANONICAL_UUID);
        AggregateId workProjectId = WorkProjectId.from(CANONICAL_UUID);
        AggregateId workItemId = WorkItemId.from(CANONICAL_UUID);

        List.of(
                        organizationId,
                        teamId,
                        workspaceId,
                        principalId,
                        artifactId,
                        credentialId,
                        workProjectId,
                        workItemId)
                .forEach(identifier -> {
                    assertEquals(UUID.fromString(CANONICAL_UUID), identifier.value());
                    assertEquals(CANONICAL_UUID, identifier.toString());
                });
    }

    @Test
    void keepsIdentifiersOfDifferentAggregateTypesDistinct() {
        assertFalse(OrganizationId.from(CANONICAL_UUID).equals(TeamId.from(CANONICAL_UUID)));
    }

    @Test
    void rejectsNullNilAndNonCanonicalUuidValues() {
        assertThrows(NullPointerException.class, () -> new OrganizationId(null));
        assertThrows(IllegalArgumentException.class, () -> new TeamId(AggregateId.NIL_UUID));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceId.from(null));
        assertThrows(IllegalArgumentException.class, () -> PrincipalId.from("not-a-uuid"));
        // UUID.fromString accepts this abbreviated form; CrewScope deliberately does not.
        assertThrows(IllegalArgumentException.class, () -> WorkItemId.from("1-1-1-1-1"));
    }
}
