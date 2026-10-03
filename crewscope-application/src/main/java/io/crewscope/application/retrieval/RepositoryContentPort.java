package io.crewscope.application.retrieval;

import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.retrieval.SourceCommit;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Read-only Port over one repository binding's tree at one exact commit (M10-I01b).
 * The port is deliberately free of chunking concerns: exclusion globs and size ceilings
 * belong to {@link io.crewscope.domain.retrieval.chunking.ChunkingPolicy}, so changing
 * them changes the policy hash and therefore the index key, never this contract.
 * Implementations throw {@link RepositoryContentFailure} with a stable code (for
 * example {@code REPOSITORY_TOO_LARGE}) for unrecoverable reads.
 */
public interface RepositoryContentPort {

    /** Deterministic file listing of one commit, sorted by path ascending. */
    List<RepositoryFileRef> listFiles(
            OrganizationId organizationId,
            TeamId teamId,
            RepositoryBindingId bindingId,
            SourceCommit commit);

    /** Full UTF-8 content of exactly one file at one commit. */
    RepositoryFileContent readFile(
            OrganizationId organizationId,
            TeamId teamId,
            RepositoryBindingId bindingId,
            SourceCommit commit,
            String path);

    /** One listed file: its repository path and exact blob size in bytes. */
    record RepositoryFileRef(String path, long sizeBytes) {

        private static final Pattern PATH = Pattern.compile("[^\\u0000\\n\\r]+");

        public RepositoryFileRef {
            path = Objects.requireNonNull(path, "path").strip();
            if (path.isEmpty() || path.length() > 1024 || !PATH.matcher(path).matches()) {
                throw new IllegalArgumentException(
                        "path must contain 1 to 1024 path characters");
            }
            if (path.startsWith("/") || path.endsWith("/")) {
                throw new IllegalArgumentException("path is relative and never a directory");
            }
            if (sizeBytes < 0) {
                throw new IllegalArgumentException("sizeBytes must not be negative");
            }
        }
    }

    /** The content of one read file; empty files carry an empty string. */
    record RepositoryFileContent(String path, String content) {

        public RepositoryFileContent {
            path = Objects.requireNonNull(path, "path").strip();
            if (path.isEmpty()) {
                throw new IllegalArgumentException("path must not be empty");
            }
            content = Objects.requireNonNull(content, "content");
        }
    }
}
