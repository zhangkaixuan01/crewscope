package io.crewscope.application.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.id.OrganizationId;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Guards the explicit tenant predicate on every Review repository read contract. */
class ReviewRepositoryTenantBoundaryTest {

    private static final List<Class<?>> REVIEW_REPOSITORIES = List.of(
            ReviewSubjectRepository.class,
            ContextPackageRepository.class,
            ReviewRequestRepository.class,
            ReviewFindingRepository.class,
            ReviewFindingObservationRepository.class,
            ReviewDecisionRepository.class,
            ReviewModificationRoundRepository.class);

    @Test
    void everyReviewReadStartsWithOrganizationId() {
        for (Class<?> repository : REVIEW_REPOSITORIES) {
            List<Method> readMethods = java.util.Arrays.stream(repository.getDeclaredMethods())
                    .filter(method -> method.getName().startsWith("find")
                            || method.getName().startsWith("exists"))
                    .toList();
            assertFalse(
                    readMethods.isEmpty(), () -> repository.getSimpleName() + " has no read method");
            for (Method method : readMethods) {
                assertTrue(method.getParameterCount() > 0, method::toGenericString);
                assertEquals(
                        OrganizationId.class,
                        method.getParameterTypes()[0],
                        method::toGenericString);
            }
        }
    }
}
