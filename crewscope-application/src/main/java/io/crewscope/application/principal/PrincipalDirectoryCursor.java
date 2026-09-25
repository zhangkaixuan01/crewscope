package io.crewscope.application.principal;

import io.crewscope.domain.shared.id.PrincipalId;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Keyset position in the directory's stable (sort key, principal id) ordering. */
public record PrincipalDirectoryCursor(String sortKey, PrincipalId principalId) {

    /** display_name is VARCHAR(200); four UTF-8 bytes per character bound the encoded sort key. */
    private static final int MAX_SORT_KEY_BYTES = 800;

    public PrincipalDirectoryCursor {
        Objects.requireNonNull(sortKey, "sortKey");
        Objects.requireNonNull(principalId, "principalId");
        if (sortKey.getBytes(StandardCharsets.UTF_8).length > MAX_SORT_KEY_BYTES) {
            throw new IllegalArgumentException("sortKey must not exceed 800 UTF-8 bytes");
        }
    }
}
