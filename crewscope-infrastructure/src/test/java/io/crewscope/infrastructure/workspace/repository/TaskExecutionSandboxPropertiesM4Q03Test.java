package io.crewscope.infrastructure.workspace.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskExecutionSandboxPropertiesM4Q03Test {

    @TempDir Path temporaryDirectory;

    @Test
    void resolvesAnAbsolutePhysicalReadOnlyDependencyCache() throws Exception {
        Path cache = Files.createDirectory(temporaryDirectory.resolve("cache"));
        Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            TaskExecutionSandboxProperties properties = new TaskExecutionSandboxProperties();
            properties.setDependencyCacheRoot(cache.toString());

            assertEquals(
                    cache.toRealPath(LinkOption.NOFOLLOW_LINKS),
                    properties.dependencyCacheRootPath().orElseThrow());
            assertEquals("/maven-cache", properties.requiredDependencyCacheMount());
        } finally {
            Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void leavesTheDependencyCacheDisabledWhenTheRootIsBlank() {
        TaskExecutionSandboxProperties properties = new TaskExecutionSandboxProperties();

        assertTrue(properties.dependencyCacheRootPath().isEmpty());
    }

    @Test
    void rejectsRelativeWritableAndSymlinkCacheRoots() throws Exception {
        TaskExecutionSandboxProperties relative = new TaskExecutionSandboxProperties();
        relative.setDependencyCacheRoot("relative/cache");
        assertThrows(IllegalArgumentException.class, relative::dependencyCacheRootPath);

        Path writable = Files.createDirectory(temporaryDirectory.resolve("writable"));
        TaskExecutionSandboxProperties writableProperties = new TaskExecutionSandboxProperties();
        writableProperties.setDependencyCacheRoot(writable.toString());
        assertThrows(IllegalArgumentException.class, writableProperties::dependencyCacheRootPath);

        Path target = Files.createDirectory(temporaryDirectory.resolve("target"));
        Path link = temporaryDirectory.resolve("link");
        Files.createSymbolicLink(link, target);
        TaskExecutionSandboxProperties symlink = new TaskExecutionSandboxProperties();
        symlink.setDependencyCacheRoot(link.toString());
        assertThrows(IllegalArgumentException.class, symlink::dependencyCacheRootPath);
    }
}
