-- M9b-Q02: coding sandboxes default to RESTRICTED_EGRESS (an isolated user-defined Docker
-- bridge with outbound NAT) instead of no network, matching how a human developer fetches
-- build dependencies. The persisted-shape checks on both the reusable WorkspacePolicy and the
-- per-attempt overlay widen from the single NONE tier to the full SandboxNetworkMode enum;
-- existing NONE rows remain valid and reusable without rewrite.
ALTER TABLE crewscope.workspace_policy DROP CONSTRAINT ck_workspace_policy_sandbox;

ALTER TABLE crewscope.workspace_policy ADD CONSTRAINT ck_workspace_policy_sandbox CHECK (
    sandbox_network_mode IN ('NONE', 'LOOPBACK_ONLY', 'RESTRICTED_EGRESS')
    AND sandbox_cpu_count >= 1
    AND sandbox_memory_mib >= 64
    AND sandbox_pids >= 1
    AND sandbox_max_command_duration_seconds BETWEEN 1 AND 3600
    AND sandbox_max_command_output_bytes > 0
    AND sandbox_read_only_root_filesystem
);

ALTER TABLE crewscope.workspace_policy_overlay DROP CONSTRAINT ck_workspace_policy_overlay_sandbox;

ALTER TABLE crewscope.workspace_policy_overlay ADD CONSTRAINT ck_workspace_policy_overlay_sandbox CHECK (
    sandbox_network_mode IN ('NONE', 'LOOPBACK_ONLY', 'RESTRICTED_EGRESS')
    AND sandbox_cpu_count >= 1
    AND sandbox_memory_mib >= 64
    AND sandbox_pids >= 1
    AND sandbox_max_command_duration_seconds BETWEEN 1 AND 3600
    AND sandbox_max_command_output_bytes > 0
    AND sandbox_read_only_root_filesystem
);
