package io.crewscope.infrastructure.persistence.review;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

/** JSON boundary for Review persistence; validates collection shapes before domain reconstruction. */
final class ReviewPersistenceJsonCodec {

    private final ObjectMapper mapper;

    ReviewPersistenceJsonCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    String serialize(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (RuntimeException failure) { throw new IllegalStateException("Review JSON cannot be serialized", failure); }
    }

    List<String> strings(String value) {
        try {
            Object decoded = mapper.readValue(value, List.class);
            if (!(decoded instanceof List<?> list)) throw new IllegalStateException("Expected a JSON array");
            return list.stream().map(item -> {
                if (!(item instanceof String)) throw new IllegalStateException("Review JSON array item must be a string");
                return (String) item;
            }).toList();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Review JSON array is invalid", failure);
        }
    }

    List<String> evidenceCoordinates(String value) {
        try {
            Object decoded = mapper.readValue(value, List.class);
            if (!(decoded instanceof List<?> list)) throw new IllegalStateException("Expected Review evidence coordinates array");
            return list.stream().map(item -> {
                if (!(item instanceof Map<?, ?> coordinate)) throw new IllegalStateException("Review evidence coordinate must be an object");
                return coordinate.get("id") + ":" + coordinate.get("sequence") + ":" + coordinate.get("evidenceHash");
            }).toList();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Review evidence coordinates are invalid", failure);
        }
    }
}
