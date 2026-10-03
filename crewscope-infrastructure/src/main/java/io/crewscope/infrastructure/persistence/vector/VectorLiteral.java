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
}
