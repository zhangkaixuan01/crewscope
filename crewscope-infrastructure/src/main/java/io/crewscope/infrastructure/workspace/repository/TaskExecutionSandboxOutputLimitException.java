package io.crewscope.infrastructure.workspace.repository;

/** Internal signal that command output exceeded the exact Workspace Sandbox budget. */
final class TaskExecutionSandboxOutputLimitException extends RuntimeException {

    private final String stdout;
    private final String stderr;

    TaskExecutionSandboxOutputLimitException(String stdout, String stderr) {
        super("Sandbox command output exceeded its configured budget", null, false, false);
        this.stdout = stdout;
        this.stderr = stderr;
    }

    String stdout() { return stdout; }

    String stderr() { return stderr; }
}
