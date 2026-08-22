package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.ExecutionWorkspace;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/** Injects bounded hidden Judge sources before Diff monitoring starts and keeps them out of Git. */
final class FilesystemCodingEvaluationJudgeHook implements CodingWorktreePreparationHook {

    static final String EXCLUDE_PATTERN =
            "/src/test/java/io/crewscope/evaluation/*JudgeTest.java";
    private static final int MAXIMUM_JUDGE_FILES = 100;
    private static final long MAXIMUM_JUDGE_BYTES = 1_048_576;
    private static final Path TEST_PACKAGE =
            Path.of("src/test/java/io/crewscope/evaluation");
    private static final ReentrantLock EXCLUDE_UPDATE_LOCK = new ReentrantLock();

    private final Path judgeRoot;
    private final String repositoryKey;

    FilesystemCodingEvaluationJudgeHook(Path judgeRoot, String repositoryKey) {
        this.judgeRoot = requirePhysicalDirectory(judgeRoot, "Judge tests root");
        this.repositoryKey = Objects.requireNonNull(repositoryKey, "repositoryKey");
    }

    @Override
    public void prepare(
            ExecutionWorkspace workspace,
            CodingTargetSnapshot target,
            ManagedRepository repository,
            ManagedWorktree worktree) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(target, "target");
        ManagedRepository managedRepository = Objects.requireNonNull(repository, "repository");
        ManagedWorktree managedWorktree = Objects.requireNonNull(worktree, "worktree");
        if (!workspace.id().equals(managedWorktree.workspaceId())
                || !workspace.repositoryKey().equals(managedRepository.repositoryKey())
                || !workspace.repositoryKey().equals(managedWorktree.repositoryKey())
                || !workspace.codingTarget().equals(target.reference())) {
            throw new IllegalStateException(
                    "Coding evaluation Judge context does not match the verified Worktree");
        }
        if (!repositoryKey.equals(workspace.repositoryKey().value())) {
            return;
        }

        Path worktreeRoot = requirePhysicalDirectory(
                managedWorktree.canonicalPath(), "Managed Worktree");
        List<Path> judges = discoverJudges(target);
        Path destination = createPhysicalDirectories(worktreeRoot, TEST_PACKAGE);
        for (Path judge : judges) {
            copyIdempotently(judge, destination.resolve(judge.getFileName().toString()));
        }
        installExclude(managedRepository.canonicalPath().resolve("info/exclude"));
        installWorktreeExclude(worktreeRoot, managedRepository.canonicalPath());
    }

    private List<Path> discoverJudges(CodingTargetSnapshot target) {
        Set<String> expectedNames = expectedJudgeNames(target);
        List<Path> judges = new ArrayList<>();
        try (var paths = Files.walk(judgeRoot, 2)) {
            paths.filter(path -> !path.equals(judgeRoot)).forEach(path -> {
                if (Files.isSymbolicLink(path)) {
                    throw new IllegalStateException(
                            "Coding evaluation Judge tree must not contain symbolic links");
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    return;
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        || !path.getFileName().toString().endsWith("JudgeTest.java")) {
                    throw new IllegalStateException(
                            "Coding evaluation Judge tree contains an unsupported entry");
                }
                if (expectedNames.contains(path.getFileName().toString())) {
                    judges.add(path);
                }
            });
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Coding evaluation Judge tests could not be enumerated", failure);
        }
        judges.sort(Comparator.comparing(Path::toString));
        if (judges.isEmpty()
                || judges.size() != expectedNames.size()
                || judges.size() > MAXIMUM_JUDGE_FILES) {
            throw new IllegalStateException(
                    "Coding evaluation Judge tests do not match the frozen allowed source paths");
        }
        long distinctNames = judges.stream()
                .map(path -> path.getFileName().toString())
                .distinct()
                .count();
        if (distinctNames != judges.size()) {
            throw new IllegalStateException(
                    "Coding evaluation Judge test names must be unique");
        }
        return List.copyOf(judges);
    }

    /**
     * Derives the hidden test classes from immutable allowed Java source paths. Installing only
     * those tests keeps an unscoped Maven verification command task-local: unrelated frozen
     * fixtures cannot fail the current run or consume its repair and token budgets.
     */
    private static Set<String> expectedJudgeNames(CodingTargetSnapshot target) {
        Set<String> names = new HashSet<>();
        for (String allowedPath : target.allowedPaths().values()) {
            String filename = Path.of(allowedPath).getFileName().toString();
            if (!filename.endsWith(".java") || filename.equals(".java")) {
                continue;
            }
            names.add(filename.substring(0, filename.length() - ".java".length())
                    + "JudgeTest.java");
        }
        if (names.isEmpty() || names.size() > MAXIMUM_JUDGE_FILES) {
            throw new IllegalStateException(
                    "Coding evaluation allowed paths do not identify bounded Java Judge tests");
        }
        return Set.copyOf(names);
    }

    private static void copyIdempotently(Path source, Path destination) {
        try {
            long size = Files.size(source);
            if (size <= 0 || size > MAXIMUM_JUDGE_BYTES) {
                throw new IllegalStateException(
                        "Coding evaluation Judge test size is outside the supported range");
            }
            byte[] content = Files.readAllBytes(source);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(destination)
                        || !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
                        || !java.util.Arrays.equals(content, Files.readAllBytes(destination))) {
                    throw new IllegalStateException(
                            "Existing Coding evaluation Judge test conflicts with the fixture");
                }
                return;
            }
            Files.write(destination, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Coding evaluation Judge test could not be installed", failure);
        }
    }

    private static Path createPhysicalDirectories(Path root, Path relative) {
        Path current = root;
        for (Path segment : relative) {
            current = current.resolve(segment.toString());
            try {
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(current)
                            || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IllegalStateException(
                                "Coding evaluation Judge destination must be physical directories");
                    }
                } else {
                    Files.createDirectory(current);
                }
            } catch (IOException failure) {
                throw new IllegalStateException(
                        "Coding evaluation Judge destination could not be created", failure);
            }
        }
        return current;
    }

    private static void installWorktreeExclude(Path worktreeRoot, Path repositoryRoot) {
        Path marker = worktreeRoot.resolve(".git");
        try {
            if (Files.isSymbolicLink(marker)
                    || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Managed Worktree Git marker is invalid");
            }
            String value = Files.readString(marker, StandardCharsets.UTF_8).strip();
            if (!value.startsWith("gitdir: ")) {
                throw new IllegalStateException("Managed Worktree Git marker is malformed");
            }
            Path gitDirectory = Path.of(value.substring("gitdir: ".length()));
            if (!gitDirectory.isAbsolute()) {
                gitDirectory = worktreeRoot.resolve(gitDirectory).normalize();
            }
            Path canonicalGitDirectory = requirePhysicalDirectory(
                    gitDirectory, "Managed Worktree Git directory");
            Path canonicalRepository = requirePhysicalDirectory(
                    repositoryRoot, "Managed repository");
            if (!canonicalGitDirectory.startsWith(canonicalRepository.resolve("worktrees"))) {
                throw new IllegalStateException(
                        "Managed Worktree Git directory is outside the repository");
            }
            Path info = canonicalGitDirectory.resolve("info");
            if (!Files.exists(info, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(info);
            } else if (Files.isSymbolicLink(info)
                    || !Files.isDirectory(info, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Managed Worktree Git info path is invalid");
            }
            installExclude(info.resolve("exclude"));
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Managed Worktree Git exclude could not be installed", failure);
        }
    }

    private static void installExclude(Path exclude) {
        EXCLUDE_UPDATE_LOCK.lock();
        try {
            try (FileChannel channel = FileChannel.open(
                            exclude,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.READ,
                            StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                if (channel.size() > 65_536) {
                    throw new IllegalStateException(
                            "Managed Git exclude file exceeds its safety limit");
                }
                ByteBuffer existingBytes = ByteBuffer.allocate((int) channel.size());
                channel.position(0);
                while (existingBytes.hasRemaining() && channel.read(existingBytes) >= 0) {
                    // Read the complete, bounded file while holding the cross-process lock.
                }
                String existing = new String(existingBytes.array(), StandardCharsets.UTF_8);
                boolean present = existing.lines().anyMatch(EXCLUDE_PATTERN::equals);
                if (!present) {
                    String prefix = existing.isEmpty() || existing.endsWith("\n") ? "" : "\n";
                    byte[] addition = (prefix + EXCLUDE_PATTERN + "\n")
                            .getBytes(StandardCharsets.UTF_8);
                    channel.position(channel.size());
                    channel.write(ByteBuffer.wrap(addition));
                    channel.force(true);
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Managed Git exclude could not be updated", failure);
        } finally {
            EXCLUDE_UPDATE_LOCK.unlock();
        }
    }

    private static Path requirePhysicalDirectory(Path path, String label) {
        Path required = Objects.requireNonNull(path, "path");
        try {
            if (Files.isSymbolicLink(required)
                    || !Files.isDirectory(required, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException(label + " must be a physical directory");
            }
            return required.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException failure) {
            throw new IllegalStateException(label + " could not be verified", failure);
        }
    }
}
