package io.crewscope.server.api;

import io.crewscope.domain.projection.ProjectionDefinitionVersion;
import io.crewscope.domain.projection.ProjectionFailureCode;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.projection.ProjectionRebuildJobId;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Transport-only validation for Operations commands. Domain value construction and stable
 * invalid_request mapping live here so endpoint handlers remain orchestration focused.
 */
final class OperationsRequestParser {

    private OperationsRequestParser() {}

    static ProjectionName projectionName(String value) {
        try { return new ProjectionName(value); }
        catch (RuntimeException failure) { throw invalid("projectionName"); }
    }

    static ProjectionGeneration generation(String value, String field) {
        try { return new ProjectionGeneration(Long.parseLong(value)); }
        catch (RuntimeException failure) { throw invalid(field); }
    }

    static ProjectionGeneration generation(Long value, String field) {
        if (value == null) throw invalid(field);
        try { return new ProjectionGeneration(value); }
        catch (RuntimeException failure) { throw invalid(field); }
    }

    static ProjectionDefinitionVersion definitionVersion(Long value) {
        if (value == null) throw invalid("expectedDefinitionVersion");
        try { return new ProjectionDefinitionVersion(value); }
        catch (RuntimeException failure) { throw invalid("expectedDefinitionVersion"); }
    }

    static ProjectionRebuildJobId rebuildJobId(String value) {
        try { return new ProjectionRebuildJobId(UUID.fromString(value)); }
        catch (RuntimeException failure) { throw invalid("rebuildJobId"); }
    }

    static ProjectionFailureCode failureCode(String value) {
        try { return new ProjectionFailureCode(value); }
        catch (RuntimeException failure) { throw invalid("failureCode"); }
    }

    static long version(Long value, String field) {
        if (value == null || value < 0) throw invalid(field);
        return value;
    }

    static long nextVersion(long value) {
        try { return Math.incrementExact(value); }
        catch (ArithmeticException failure) { throw new IllegalStateException("Projection version is exhausted", failure); }
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw invalid(field);
        return value.strip();
    }

    static <T> T requireBody(T value, String field) {
        if (value == null) throw invalid(field);
        return value;
    }

    private static ApiRequestException invalid(String field) {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid operations command field",
                Map.of("field", field));
    }
}
