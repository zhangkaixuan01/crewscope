package io.crewscope.infrastructure.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.github.GitHubPushException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Security contract for the M5-I09 one-action credential window. */
class GitAskPassSessionM5I09Test {

    @TempDir Path temporaryDirectory;

    @Test
    void keepsSecretOutOfArgvEnvironmentAndProgramThenDeletesEveryFile() throws Exception {
        String token = "ghs_m5_i09_action_window_secret";
        byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
        Path program;
        Path secret;
        try (GitAskPassSession session = GitAskPassSession.open(
                temporaryDirectory.resolve("credentials"), bytes)) {
            program = session.program();
            secret = session.secretFile();
            var askPassEnvironment = session.environment();
            Map<String, String> environment = Map.of(
                    "GIT_ASKPASS", askPassEnvironment.askPassProgram().toString(),
                    "GIT_ASKPASS_REQUIRE", "force",
                    "CREWSCOPE_GITHUB_TOKEN_FILE", askPassEnvironment.secretFile().toString());
            assertTrue(environment.values().stream().noneMatch(value -> value.contains(token)));
            assertFalse(Files.readString(program).contains(token));
            assertEquals(Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(secret));
            assertEquals(Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(program));

            ProcessBuilder builder = new ProcessBuilder(program.toString(), "Password for https");
            builder.environment().clear();
            builder.environment().putAll(environment);
            Process process = builder.start();
            String resolved = new String(
                    process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            assertEquals(token, resolved);
        }
        assertFalse(Files.exists(program));
        assertFalse(Files.exists(secret));
    }

    @Test
    void rejectsCredentialProtocolControlCharactersBeforeCreatingFiles() {
        assertThrows(
                GitHubPushException.class,
                () -> GitAskPassSession.open(
                        temporaryDirectory.resolve("invalid"),
                        "token\nsecond-line".getBytes(StandardCharsets.UTF_8)));
    }
}
