---
name: java-spring-v1
description: CrewScope's version-pinned Java and Spring Boot repository implementation rules.
---

# Java and Spring Boot Repository Skill v1

## Repository analysis

1. Initialize exactly these two Todos in one `todo_write` call: `Implement requested change` and
   `Verify and deliver`. Do not create Todos for Skill loading, repository analysis, diff
   inspection or the delivery summary.
2. Read the target production files and only the build or neighboring files needed for the change.
3. Locate relevant conventions with bounded repository search; do not reread content already
   present in a tool result or final diff.
4. Preserve Java 17 compatibility, package boundaries and constructor injection.

## Implementation

1. Prefer a small domain-level fix over broad rewrites.
2. Add comments for security boundaries, non-obvious invariants and recovery decisions.
3. Keep HTTP, application and infrastructure concerns in their existing layers.
4. Use `Locale.ROOT`, explicit time sources and deterministic ordering where applicable.
5. Preserve validation, authorization, tenant scope, idempotency and secret handling.

## Verification

1. Run each acceptance command published by the platform once after editing. Do not add a separate
   compile or full-suite command unless an acceptance failure must be diagnosed or repaired.
2. Treat exit code, timeout and platform TestEvidence as authoritative.
3. Inspect Git status and the final diff once, and confirm every changed path is allowed.
4. Do not update Todos after intermediate steps. After verification, mark both fixed Todos
   complete together in the second and final `todo_write` call, then return the structured result
   immediately. The entire run may call `todo_write` at most twice.
5. Report remaining risks without converting them into successful evidence.
