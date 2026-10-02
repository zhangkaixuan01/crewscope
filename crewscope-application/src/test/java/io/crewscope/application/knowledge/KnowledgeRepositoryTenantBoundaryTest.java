package io.crewscope.application.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the explicit tenant predicate on every Knowledge repository read contract, and
 * that the port offers no mutation path for immutable version rows beyond the atomic
 * append inside {@code save}.
 */
class KnowledgeRepositoryTenantBoundaryTest {

    @Test
    void everyReadStartsWithOrganizationIdThenTeamId() {
        List<Method> readMethods = Arrays.stream(KnowledgeRepository.class.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("find")
                        || method.getName().startsWith("exists"))
                .toList();

        assertFalse(readMethods.isEmpty(), "KnowledgeRepository has no read method");
        for (Method method : readMethods) {
            assertTrue(method.getParameterCount() > 1, method::toGenericString);
            assertEquals(OrganizationId.class, method.getParameterTypes()[0], method::toGenericString);
            assertEquals(TeamId.class, method.getParameterTypes()[1], method::toGenericString);
        }
    }

    @Test
    void versionRowsHaveNoStandaloneMutationPath() {
        List<String> versionMutators = Arrays.stream(KnowledgeRepository.class.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("update")
                        || method.getName().startsWith("delete")
                        || method.getName().startsWith("remove")
                        || method.getName().startsWith("merge"))
                .map(Method::getName)
                .toList();

        assertEquals(List.of(), versionMutators);
    }
}
