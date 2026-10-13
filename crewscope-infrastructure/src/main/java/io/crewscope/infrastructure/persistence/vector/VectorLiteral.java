package io.crewscope.infrastructure.persistence.vector;

import java.util.Objects;

/**
 * Text-literal geometry binding for pgvector columns: vectors cross the wire as
 * {@code "[c0,c1,...]"} text bound through {@code CAST(? AS public.vector)}, so no
 * PGvector Java dependency exists. Scientific notation is safe because Postgres parses
 * vector text under a C-locale numeric context (strtof), which {@link Float#toString}
 * output always satisfies.
 */
public final class VectorLiteral {

    private VectorLiteral() {}

    public static String of(float[] embedding) {
        Objects.requireNonNull(embedding, "embedding");
        StringBuilder literal = new StringBuilder(embedding.length * 12 + 2);
        literal.append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(Float.toString(embedding[i]));
        }
        return literal.append(']').toString();
    }

    /** Parses the pgvector text form {@code [c0,c1,...]} back into produced geometry. */
    public static float[] parse(String literal) {
        String required = Objects.requireNonNull(literal, "literal");
        String inner = required.substring(1, required.length() - 1);
        if (inner.isBlank()) {
            return new float[0];
        }
        String[] components = inner.split(",");
        float[] values = new float[components.length];
        for (int i = 0; i < components.length; i++) {
            values[i] = Float.parseFloat(components[i]);
        }
        return values;
    }
}
