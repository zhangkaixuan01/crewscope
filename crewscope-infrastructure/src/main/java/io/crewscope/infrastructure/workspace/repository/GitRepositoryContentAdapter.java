package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.retrieval.KnowledgeIndexFailureCodes;
import io.crewscope.application.retrieval.RepositoryContentFailure;
import io.crewscope.application.retrieval.RepositoryContentPort;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.retrieval.SourceCommit;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.infrastructure.workspace.git.GitCommandException;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@link RepositoryContentPort} over one binding's managed bare repository
 * (M10-I01b): the binding resolves to its {@link RepositoryKey}, the resolver
 * validates the canonical bare repository, and reads go through the two fixed
 * Git entries {@code ls-tree -r -l -z} and {@code cat-file blob} — no checkout,
 * no pager, no shell. Chunking concerns (exclusion globs, size ceilings) stay in
 * {@link io.crewscope.domain.retrieval.chunking.ChunkingPolicy} by contract; this
 * adapter only reports unrecoverable reads as stable failure codes.
 */
public final class GitRepositoryContentAdapter implements RepositoryContentPort {

    private final NamedParameterJdbcTemplate jdbc;
    private final ManagedRepositoryResolver resolver;
    private final GitCommandExecutor gitCommands;

    public GitRepositoryContentAdapter(
            NamedParameterJdbcTemplate jdbc,
            ManagedRepositoryResolver resolver,
            GitCommandExecutor gitCommands) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.gitCommands = Objects.requireNonNull(gitCommands, "gitCommands");
    }

    @Override
    public List<RepositoryFileRef> listFiles(
            OrganizationId organizationId,
            TeamId teamId,
            RepositoryBindingId bindingId,
            SourceCommit commit) {
        ManagedRepository repository =
                resolveRepository(organizationId, teamId, bindingId);
        RepositoryCommitId commitId = commitId(commit);
        String listing;
        try {
            listing = gitCommands.listTreeFiles(repository.canonicalPath(), commitId);
        } catch (GitCommandException failure) {
            throw contentFailure(failure);
        }
        List<RepositoryFileRef> files = new ArrayList<>();
        for (String record : listing.split("\0", -1)) {
            fileRef(record).ifPresent(files::add);
        }
        files.sort(Comparator.comparing(RepositoryFileRef::path));
        return List.copyOf(files);
    }

    @Override
    public RepositoryFileContent readFile(
            OrganizationId organizationId,
            TeamId teamId,
            RepositoryBindingId bindingId,
            SourceCommit commit,
            String path) {
        Objects.requireNonNull(path, "path");
        ManagedRepository repository =
                resolveRepository(organizationId, teamId, bindingId);
        String content;
        try {
            content = gitCommands.readFileAtCommit(
                    repository.canonicalPath(), commitId(commit), path);
        } catch (GitCommandException failure) {
            throw contentFailure(failure);
        }
        return new RepositoryFileContent(path, content);
    }

    // ------------------------------------------------------------------ resolution

    private ManagedRepository resolveRepository(
            OrganizationId organizationId, TeamId teamId, RepositoryBindingId bindingId) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(bindingId, "bindingId");
        String keyValue;
        try {
            keyValue = jdbc.queryForObject(
                    """
                    SELECT repository_key FROM crewscope.repository_binding
                    WHERE organization_id = :organizationId
                      AND team_id = :teamId AND id = :bindingId
                    """,
                    new MapSqlParameterSource(Map.of(
                            "organizationId", organizationId.value(),
                            "teamId", teamId.value(),
                            "bindingId", bindingId.value())),
                    String.class);
        } catch (org.springframework.dao.EmptyResultDataAccessException missing) {
            throw new RepositoryContentFailure(
                    KnowledgeIndexFailureCodes.REPOSITORY_UNAVAILABLE,
                    "repository binding does not exist");
        }
        try {
            RepositoryKey key = RepositoryKey.parse(
                    Objects.requireNonNull(keyValue, "repository_key"));
            return resolver.resolve(key);
        } catch (RepositoryPreflightException failure) {
            throw new RepositoryContentFailure(
                    KnowledgeIndexFailureCodes.REPOSITORY_UNAVAILABLE,
                    "managed repository could not be resolved");
        } catch (RuntimeException invalidKey) {
            throw new RepositoryContentFailure(
                    KnowledgeIndexFailureCodes.REPOSITORY_UNAVAILABLE,
                    "managed repository key is not parsable");
        }
    }

    /** Parses one {@code ls-tree} record: {@code mode SP type SP object SP size TAB path}. */
    private static Optional<RepositoryFileRef> fileRef(String record) {
        if (record.isBlank()) {
            return Optional.empty();
        }
        int separator = record.indexOf('\t');
        if (separator < 0) {
            return Optional.empty();
        }
        String path = record.substring(separator + 1);
        String[] meta = record.substring(0, separator).strip().split("\\s+");
        if (meta.length != 4 || !"blob".equals(meta[1])) {
            return Optional.empty();
        }
        long sizeBytes;
        try {
            sizeBytes = Long.parseLong(meta[3]);
        } catch (NumberFormatException notABlobSize) {
            return Optional.empty();
        }
        return Optional.of(new RepositoryFileRef(path, sizeBytes));
    }

    private static RepositoryCommitId commitId(SourceCommit commit) {
        return new RepositoryCommitId(
                Objects.requireNonNull(commit, "commit").value());
    }

    private static RepositoryContentFailure contentFailure(GitCommandException failure) {
        String code = switch (failure.error()) {
            case OUTPUT_LIMIT -> KnowledgeIndexFailureCodes.REPOSITORY_TOO_LARGE;
            case NOT_A_REPOSITORY, INVALID_REFERENCE ->
                    KnowledgeIndexFailureCodes.REPOSITORY_UNAVAILABLE;
            default -> KnowledgeIndexFailureCodes.REPOSITORY_READ_FAILED;
        };
        return new RepositoryContentFailure(
                code, "repository content read failed: " + failure.error());
    }
}
