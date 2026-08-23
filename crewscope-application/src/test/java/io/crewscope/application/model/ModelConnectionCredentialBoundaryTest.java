package io.crewscope.application.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.credential.CredentialSecret;
import io.crewscope.application.credential.CredentialStore;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelCredentialBinding;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ModelConnectionCredentialBoundaryTest {

    @Test
    void keepsSecretInputAtTheCredentialStoreBoundaryOnly() {
        assertEquals(
                2,
                Arrays.stream(CredentialStore.class.getMethods())
                        .filter(ModelConnectionCredentialBoundaryTest::acceptsCredentialSecret)
                        .count());
        assertTrue(Arrays.stream(ModelConnection.class.getMethods())
                .noneMatch(ModelConnectionCredentialBoundaryTest::acceptsSecretShapedInput));
        assertTrue(Arrays.stream(ModelConnectionRepository.class.getMethods())
                .noneMatch(ModelConnectionCredentialBoundaryTest::acceptsSecretShapedInput));
        assertTrue(Arrays.stream(ModelCredentialBinding.class.getRecordComponents())
                .noneMatch(ModelConnectionCredentialBoundaryTest::isSecretShapedComponent));
    }

    private static boolean acceptsCredentialSecret(Method method) {
        return Arrays.stream(method.getParameterTypes())
                .anyMatch(CredentialSecret.class::equals);
    }

    private static boolean acceptsSecretShapedInput(Method method) {
        return Stream.of(method.getParameters())
                .anyMatch(ModelConnectionCredentialBoundaryTest::isSecretShapedParameter);
    }

    private static boolean isSecretShapedParameter(Parameter parameter) {
        return isSecretContainer(parameter.getType())
                || secretNames().stream().anyMatch(parameter.getName().toLowerCase()::contains);
    }

    private static boolean isSecretShapedComponent(RecordComponent component) {
        return isSecretContainer(component.getType())
                || secretNames().stream().anyMatch(component.getName().toLowerCase()::contains);
    }

    private static boolean isSecretContainer(Class<?> type) {
        return type.equals(CredentialSecret.class)
                || type.equals(byte[].class)
                || type.equals(char[].class);
    }

    private static Set<String> secretNames() {
        return Set.of("secret", "apikey", "password", "authorization", "bearer");
    }
}
