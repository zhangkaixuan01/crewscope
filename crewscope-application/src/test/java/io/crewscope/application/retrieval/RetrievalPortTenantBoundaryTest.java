package io.crewscope.application.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.retrieval.RepositoryIndexKey;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Guards the tenant coordinate on the retrieval read ports and index identity. */
class RetrievalPortTenantBoundaryTest {

    @Test
    void manifestLookupStartsWithOrganizationIdThenTeamId() {
        List<Method> readMethods = Arrays.stream(InjectionManifestRepository.class.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("find")
                        || method.getName().startsWith("exists"))
                .toList();

        assertFalse(readMethods.isEmpty(), "InjectionManifestRepository has no read method");
        for (Method method : readMethods) {
            assertTrue(method.getParameterCount() > 1, method::toGenericString);
            assertEquals(OrganizationId.class, method.getParameterTypes()[0], method::toGenericString);
            assertEquals(TeamId.class, method.getParameterTypes()[1], method::toGenericString);
        }
    }

    @Test
    void activeGenerationLookupIsScopedByTheFullIndexCoordinate() throws NoSuchMethodException {
        java.lang.reflect.Method lookup =
                GenerationCatalog.class.getDeclaredMethod(
                        "findActiveGeneration", io.crewscope.domain.retrieval.RepositoryIndexKey.class);

        assertEquals(1, lookup.getParameterCount());
        assertEquals(
                io.crewscope.domain.retrieval.RepositoryIndexKey.class,
                lookup.getParameterTypes()[0]);
    }

    @Test
    void indexIdentityCarriesTheCompositeTenantCoordinate() {
        List<String> components = Arrays.stream(RepositoryIndexKey.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertEquals(
                List.of(
                        "organizationId",
                        "teamId",
                        "repositoryBindingId",
                        "sourceCommit",
                        "chunkingPolicyHash",
                        "embeddingModelRevision"),
                components);
    }
}
